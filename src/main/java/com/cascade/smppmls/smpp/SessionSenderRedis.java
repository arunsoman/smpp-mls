package com.cascade.smppmls.smpp;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;

import com.cascade.smppmls.model.QueuedMessage;
import com.cascade.smppmls.model.UpdateRecord;
import com.cascade.smppmls.service.RedisQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsmpp.bean.*;
import org.jsmpp.session.SMPPSession;

import com.cascade.smppmls.util.SmppAddressUtil;
import com.cascade.smppmls.util.AtomicDouble;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * SessionSender - consumes messages from Redis queue and submits to SMPP.
 * Updates are sent to ClickHouse asynchronously via bulk updater.
 */
@Slf4j
public class SessionSenderRedis implements Runnable {

    private final String sessionKey;
    private final SMPPSession session;
    private final String serviceType;
    private final String defaultSourceAddress;
    private final int tps;
    private final int hpMaxPerSecond;
    private final RedisQueueService redisQueueService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.ExecutorService submitExecutor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // runtime tokens - using AtomicDouble for thread safety
    private final AtomicDouble tokens;
    private final AtomicDouble hpTokens;

    private ScheduledFuture<?> future;
    private volatile boolean running = true;

    public SessionSenderRedis(String sessionKey, SMPPSession session, String serviceType, String defaultSourceAddress,
                         int tps, int hpMaxPercentage, 
                         RedisQueueService redisQueueService,
                         RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper,
                         java.util.concurrent.ExecutorService submitExecutor, 
                         io.micrometer.core.instrument.MeterRegistry meterRegistry,
                         org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.serviceType = (serviceType != null) ? serviceType : "";
        this.defaultSourceAddress = (defaultSourceAddress != null) ? defaultSourceAddress : "";
        this.tps = Math.max(1, tps);
        this.hpMaxPerSecond = Math.max(0, (int) Math.ceil(this.tps * (hpMaxPercentage / 100.0)));
        this.redisQueueService = redisQueueService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.submitExecutor = submitExecutor;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.tokens = new AtomicDouble(this.tps); // start full
        this.hpTokens = new AtomicDouble(this.hpMaxPerSecond);
        
        log.info("[{}] SessionSenderRedis initialized: TPS={}, HP_MAX={}, serviceType='{}', defaultSourceAddress='{}'", 
            sessionKey, this.tps, this.hpMaxPerSecond, this.serviceType, this.defaultSourceAddress);
    }

    public void setScheduledFuture(ScheduledFuture<?> future) {
        this.future = future;
    }

    public void cancel() {
        running = false;
        if (future != null) future.cancel(true);
    }

    @Override
    public void run() {
        try {
            // Check if session is bound before processing
            if (session == null || !session.getSessionState().isBound()) {
                log.debug("[{}] Session not bound, skipping message processing", sessionKey);
                return;
            }
            
            // Refill tokens atomically
            tokens.updateAndGet(current -> Math.min(current + tps, tps));
            hpTokens.updateAndGet(current -> Math.min(current + hpMaxPerSecond, hpMaxPerSecond));

            log.debug("[{}] Tick: tokens={}, hpTokens={}", sessionKey, tokens.get(), hpTokens.get());

            // First send HP messages up to hpMaxPerSecond
            int toSendHp = (int)Math.floor(hpTokens.get());
            if (toSendHp > 0) {
                int hpSent = processMessages("HIGH", toSendHp, true);
                if (hpSent > 0) {
                    log.info("[{}] Submitted {} HP messages", sessionKey, hpSent);
                }
            }

            // Then send NP messages with remaining tokens
            if (tokens.get() > 0) {
                int npCount = (int)Math.floor(tokens.get());
                int npSent = processMessages("NORMAL", npCount, false);
                if (npSent > 0) {
                    log.info("[{}] Submitted {} NP messages", sessionKey, npSent);
                }
            }
        } catch (Exception ex) {
            log.error("[{}] Error in sender tick: {}", sessionKey, ex.getMessage(), ex);
        }
    }

    private int processMessages(String priority, int maxCount, boolean isHighPriority) {
        int sent = 0;
        
        for (int i = 0; i < maxCount && tokens.get() > 0; i++) {
            // Pop message from Redis queue
            QueuedMessage msg = redisQueueService.popFromQueue(sessionKey);
            if (msg == null) {
                break; // Queue empty
            }
            
            // Check priority matches
            if (!priority.equals(msg.getPriority())) {
                // Wrong priority, skip (this shouldn't happen with separate queues)
                log.warn("[{}] Popped message with wrong priority: expected={}, got={}", 
                    sessionKey, priority, msg.getPriority());
                continue;
            }
            
            // Submit message async
            submitMessageAsync(msg);
            
            // Consume tokens
            tokens.updateAndGet(current -> Math.max(0.0, current - 1.0));
            if (isHighPriority) {
                hpTokens.updateAndGet(current -> Math.max(0.0, current - 1.0));
            }
            
            sent++;
            
            if (tokens.get() <= 0.0) break;
        }
        
        return sent;
    }

    private void submitMessageAsync(QueuedMessage msg) {
        submitExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            long queueDuration = startTime - msg.getQueuedAt();
            
            try {
                // Determine proper TON/NPI for source and destination addresses
                String sourceAddress = (msg.getSourceAddr() != null && !msg.getSourceAddr().isEmpty()) 
                    ? msg.getSourceAddr() : defaultSourceAddress;
                SmppAddressUtil.AddressInfo sourceInfo = SmppAddressUtil.getSourceAddressInfo(sourceAddress);
                SmppAddressUtil.AddressInfo destInfo = SmppAddressUtil.getDestinationAddressInfo(msg.getMsisdn());
                
                log.debug("[{}] Submitting: src={} (TON={}, NPI={}), dest={} (TON={}, NPI={})",
                    sessionKey, sourceInfo.getAddress(), sourceInfo.getTon(), sourceInfo.getNpi(),
                    destInfo.getAddress(), destInfo.getTon(), destInfo.getNpi());
                
                // Submit and get response
                var submitResult = session.submitShortMessage(
                    serviceType,
                    sourceInfo.getTon(),
                    sourceInfo.getNpi(),
                    sourceInfo.getAddress(),
                    destInfo.getTon(),
                    destInfo.getNpi(),
                    destInfo.getAddress(),
                    new ESMClass(),
                    (byte)0,
                    (byte)1,
                    null,
                    null,
                    new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE),
                    (byte)0,
                    new GeneralDataCoding(Alphabet.ALPHA_DEFAULT, MessageClass.CLASS1, false),
                    (byte)0,
                    msg.getMessage().getBytes(StandardCharsets.UTF_8)
                );
                
                long responseTime = System.currentTimeMillis() - startTime;
                
                // Extract message ID from result
                String smscMsgId = (submitResult != null) ? submitResult.getMessageId() : null;

                if (smscMsgId != null) {
                    log.info("[{}] Sent message id={} smsc_msg_id={} src={} dest={} response_time={}ms queue_time={}ms", 
                        sessionKey, msg.getId(), smscMsgId, sourceInfo.getAddress(), destInfo.getAddress(), 
                        responseTime, queueDuration);
                    
                    // Add to ClickHouse update queue (async)
                    addToClickHouseUpdateQueue(msg.getId(), "SENT", smscMsgId, queueDuration, null);
                    
                    // Update metrics
                    meterRegistry.counter("smpp.outbound.sent", "priority", msg.getPriority(), "session", sessionKey).increment();
                    meterRegistry.timer("smpp.submit.response.time", "session", sessionKey)
                        .record(responseTime, java.util.concurrent.TimeUnit.MILLISECONDS);
                    meterRegistry.timer("smpp.queue.duration", "session", sessionKey)
                        .record(queueDuration, java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    // Publish events
                    if (eventPublisher != null) {
                        eventPublisher.publishEvent(new com.cascade.smppmls.event.MessageSentEvent(this, msg.getId()));
                    }
                }

            } catch (org.jsmpp.extra.NegativeResponseException nre) {
                // SMSC rejected the submit_sm with an error code
                long responseTime = System.currentTimeMillis() - startTime;
                int commandStatus = nre.getCommandStatus();
                String errorMsg = String.format("SMSC_ERROR: %s (0x%08X)", nre.getMessage(), commandStatus);
                
                log.warn("[{}] SMSC rejected message id={} status=0x{} error={} response_time={}ms", 
                    sessionKey, msg.getId(), Integer.toHexString(commandStatus), nre.getMessage(), responseTime);
                
                // Mark as FAILED in ClickHouse (async)
                addToClickHouseUpdateQueue(msg.getId(), "FAILED", null, queueDuration, errorMsg);
                
                // Update metrics
                meterRegistry.counter("smpp.outbound.failed", "session", sessionKey, "error_code", 
                    String.format("0x%08X", commandStatus)).increment();

            } catch (Exception e) {
                // Other errors (network, timeout, etc.)
                log.error("[{}] Error submitting message id={}: {}", sessionKey, msg.getId(), e.getMessage(), e);
                
                String errorMsg = "SUBMIT_ERROR: " + e.getMessage();
                addToClickHouseUpdateQueue(msg.getId(), "FAILED", null, queueDuration, errorMsg);
                
                meterRegistry.counter("smpp.outbound.errors", "session", sessionKey).increment();
            }
        });
    }

    private void addToClickHouseUpdateQueue(Long messageId, String status, String smscMsgId, 
                                           Long queueDuration, String errorMessage) {
        try {
            UpdateRecord update = UpdateRecord.builder()
                .id(messageId)
                .status(status)
                .smscMsgId(smscMsgId)
                .queueDuration(queueDuration)
                .errorMessage(errorMessage)
                .build();
            
            String updateJson = objectMapper.writeValueAsString(update);
            redisTemplate.opsForSet().add("pending:clickhouse:updates", updateJson);
            
            log.debug("[{}] Added update for message {} to ClickHouse queue (status={})", 
                sessionKey, messageId, status);
        } catch (Exception e) {
            log.error("[{}] Failed to add update to ClickHouse queue for message {}", 
                sessionKey, messageId, e);
        }
    }
}
