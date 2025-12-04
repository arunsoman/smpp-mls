package com.cascade.smppmls.smpp;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;

import lombok.extern.slf4j.Slf4j;
import org.jsmpp.bean.*;
import org.jsmpp.session.SMPPSession;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.util.SmppAddressUtil;
import com.cascade.smppmls.util.AtomicDouble;

@Slf4j
public class SessionSender implements Runnable {

    private final String sessionKey;
    private final SMPPSession session;
    private final String serviceType;
    private final String defaultSourceAddress;
    private final int tps;
    private final int hpMaxPerSecond;
    private final SmsOutboundRepository outboundRepository;
    private final java.util.concurrent.ExecutorService submitExecutor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // runtime tokens - using AtomicDouble for thread safety
    private final AtomicDouble tokens;
    private final AtomicDouble hpTokens;

    private ScheduledFuture<?> future;

    public SessionSender(String sessionKey, SMPPSession session, String serviceType, String defaultSourceAddress,
                         int tps, int hpMaxPercentage, 
                         SmsOutboundRepository outboundRepository, 
                         java.util.concurrent.ExecutorService submitExecutor, 
                         io.micrometer.core.instrument.MeterRegistry meterRegistry,
                         org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.serviceType = (serviceType != null) ? serviceType : "";
        this.defaultSourceAddress = (defaultSourceAddress != null) ? defaultSourceAddress : "";
        this.tps = Math.max(1, tps);
        this.hpMaxPerSecond = Math.max(0, (int) Math.ceil(this.tps * (hpMaxPercentage / 100.0)));
        this.outboundRepository = outboundRepository;
        this.submitExecutor = submitExecutor;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.tokens = new AtomicDouble(this.tps); // start full
        this.hpTokens = new AtomicDouble(this.hpMaxPerSecond);
        
        log.info("[{}] SessionSender initialized: TPS={}, HP_MAX={}, serviceType='{}', defaultSourceAddress='{}'", 
            sessionKey, this.tps, this.hpMaxPerSecond, this.serviceType, this.defaultSourceAddress);
    }

    public void setScheduledFuture(ScheduledFuture<?> future) {
        this.future = future;
    }

    public void cancel() {
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
            
            // refill tokens atomically
            tokens.updateAndGet(current -> Math.min(current + tps, tps));
            hpTokens.updateAndGet(current -> Math.min(current + hpMaxPerSecond, hpMaxPerSecond));

            log.debug("[{}] Tick: tokens={}, hpTokens={}", sessionKey, tokens.get(), hpTokens.get());

            // first send HP messages up to hpMaxPerSecond
            int toSendHp = (int)Math.floor(hpTokens.get());
            if (toSendHp > 0) {
                var page = outboundRepository.findByStatusAndSessionIdAndPriority("QUEUED", sessionKey, "HIGH", org.springframework.data.domain.PageRequest.of(0, toSendHp));
                long hpQueued = page.getTotalElements();
                if (hpQueued > 0) {
                    log.debug("[{}] HP check: toSend={}, queued={}, found={}", sessionKey, toSendHp, hpQueued, page.getContent().size());
                }
                
                int hpSent = 0;
                for (SmsOutboundEntity e : page.getContent()) {
                    submitMessageAsync(e);
                    tokens.updateAndGet(current -> Math.max(0.0, current - 1.0));
                    hpTokens.updateAndGet(current -> Math.max(0.0, current - 1.0));
                    hpSent++;
                    if (tokens.get() <= 0.0) break;
                }
                if (hpSent > 0) {
                    log.info("[{}] Submitted {} HP messages", sessionKey, hpSent);
                }
            }

            // then send NP messages with remaining tokens
            if (tokens.get() > 0) {
                int npCount = (int)Math.floor(tokens.get());
                var page = outboundRepository.findByStatusAndSessionIdAndPriority("QUEUED", sessionKey, "NORMAL", org.springframework.data.domain.PageRequest.of(0, npCount));
                long npQueued = page.getTotalElements();
                if (npQueued > 0) {
                    log.debug("[{}] NP check: toSend={}, queued={}, found={}", sessionKey, npCount, npQueued, page.getContent().size());
                }
                
                int npSent = 0;
                for (SmsOutboundEntity e : page.getContent()) {
                    submitMessageAsync(e);
                    tokens.updateAndGet(current -> Math.max(0.0, current - 1.0));
                    npSent++;
                    if (tokens.get() <= 0.0) break;
                }
                if (npSent > 0) {
                    log.info("[{}] Submitted {} NP messages", sessionKey, npSent);
                }
            }
        } catch (Exception ex) {
            log.error("[{}] Error in sender tick: {}", sessionKey, ex.getMessage(), ex);
        }
    }

    private void submitMessageAsync(SmsOutboundEntity e) {
        submitExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                // Determine proper TON/NPI for source and destination addresses
                // Use message's source address if provided, otherwise use session's default
                String sourceAddress = (e.getSourceAddr() != null && !e.getSourceAddr().isEmpty()) 
                    ? e.getSourceAddr() : defaultSourceAddress;
                SmppAddressUtil.AddressInfo sourceInfo = SmppAddressUtil.getSourceAddressInfo(sourceAddress);
                SmppAddressUtil.AddressInfo destInfo = SmppAddressUtil.getDestinationAddressInfo(e.getMsisdn());
                
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
                    e.getMessage().getBytes(StandardCharsets.UTF_8)
                );
                
                long responseTime = System.currentTimeMillis() - startTime;
                
                // Extract message ID from result
                String messageId = (submitResult != null) ? submitResult.getMessageId() : null;

                // Track submit_sm_resp details
                e.setSubmitResponseTimeMs(responseTime);
                e.setSubmitSmStatus(0); // ESME_ROK
                e.setSentAt(java.time.Instant.now());
                
                if (messageId != null) {
                    String smscId = messageId;
                    e.setSmscMsgId(smscId);
                    e.setStatus("SENT");
                    outboundRepository.save(e);
                    log.info("[{}] Sent message id={} smsc_msg_id={} src={} dest={} response_time={}ms", 
                        sessionKey, e.getId(), smscId, sourceInfo.getAddress(), destInfo.getAddress(), responseTime);
                    meterRegistry.counter("smpp.outbound.sent", "priority", e.getPriority(), "session", sessionKey).increment();
                    meterRegistry.timer("smpp.submit.response.time", "session", sessionKey).record(responseTime, java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    // Publish event to clear any alerts
                    if (eventPublisher != null) {
                        eventPublisher.publishEvent(new com.cascade.smppmls.event.MessageSentEvent(this, e.getId()));
                    }
                }

            } catch (org.jsmpp.extra.NegativeResponseException nre) {
                // SMSC rejected the submit_sm with an error code
                long responseTime = System.currentTimeMillis() - startTime;
                int commandStatus = nre.getCommandStatus();
                String errorMsg = String.format("SMSC_ERROR: %s (0x%08X)", nre.getMessage(), commandStatus);
                
                log.warn("[{}] SMSC rejected message id={} status=0x{} error={} response_time={}ms", 
                    sessionKey, e.getId(), Integer.toHexString(commandStatus), nre.getMessage(), responseTime);
                
                try {
                    e.setSubmitSmStatus(commandStatus);
                    e.setSubmitSmError(errorMsg);
                    e.setSubmitResponseTimeMs(responseTime);
                    
                    // Check if error is retryable
                    boolean retryable = isRetryableError(commandStatus);
                    
                    if (retryable) {
                        int nextCount = (e.getRetryCount() == null ? 0 : e.getRetryCount()) + 1;
                        e.setRetryCount(nextCount);
                        e.setStatus("RETRY");
                        // compute backoff
                        long base = 1000L;
                        long cap = 60_000L;
                        long delay = base * (1L << Math.max(0, nextCount - 1));
                        if (delay > cap) delay = cap;
                        double jitter = 0.1 * delay;
                        long jittered = delay - (long)jitter + (long)(Math.random() * (2 * jitter));
                        e.setNextRetryAt(java.time.Instant.now().plusMillis(Math.max(0, jittered)));
                        e.setLastAttemptAt(java.time.Instant.now());
                        log.info("[{}] Message id={} marked for retry (count={}, status=0x{})", 
                            sessionKey, e.getId(), nextCount, Integer.toHexString(commandStatus));
                    } else {
                        // Permanent failure
                        e.setStatus("FAILED");
                        log.error("[{}] Message id={} permanently failed with status=0x{}", 
                            sessionKey, e.getId(), Integer.toHexString(commandStatus));
                    }
                    
                    outboundRepository.save(e);
                    meterRegistry.counter("smpp.outbound.rejected", 
                        "session", sessionKey, 
                        "status", String.format("0x%08X", commandStatus)).increment();
                } catch (Exception ex2) {
                    log.error("[{}] Error updating rejection status for id={}: {}", sessionKey, e.getId(), ex2.getMessage());
                }
                
            } catch (Exception se) {
                // Other exceptions (timeout, connection error, etc.)
                long responseTime = System.currentTimeMillis() - startTime;
                log.warn("[{}] submit exception id={} : {} response_time={}ms", sessionKey, e.getId(), se.getMessage(), responseTime);
                try {
                    e.setSubmitSmError(se.getMessage());
                    e.setSubmitResponseTimeMs(responseTime);
                    
                    int nextCount = (e.getRetryCount() == null ? 0 : e.getRetryCount()) + 1;
                    e.setRetryCount(nextCount);
                    e.setStatus("RETRY");
                    // compute backoff: base * 2^(retryCount-1) with jitter
                    long base = 1000L;
                    long cap = 60_000L;
                    long delay = base * (1L << Math.max(0, nextCount - 1));
                    if (delay > cap) delay = cap;
                    // add jitter +/-10%
                    double jitter = 0.1 * delay;
                    long jittered = delay - (long)jitter + (long)(Math.random() * (2 * jitter));
                    e.setNextRetryAt(java.time.Instant.now().plusMillis(Math.max(0, jittered)));
                    e.setLastAttemptAt(java.time.Instant.now());
                    outboundRepository.save(e);
                    log.info("[{}] Marked message id={} for retry (count={}, nextRetryAt={})", sessionKey, e.getId(), e.getRetryCount(), e.getNextRetryAt());
                    meterRegistry.counter("smpp.outbound.failed", "priority", e.getPriority(), "session", sessionKey).increment();
                } catch (Exception ex2) {
                    log.error("[{}] Error updating retry status for id={}: {}", sessionKey, e.getId(), ex2.getMessage());
                }
            } catch (Throwable ex) {
                log.error("[{}] Unexpected submit error id={}: {}", sessionKey, e.getId(), ex.getMessage());
            }
        });
    }
    
    /**
     * Determine if an SMPP error code is retryable
     */
    private boolean isRetryableError(int commandStatus) {
        // Retryable errors (temporary failures)
        return switch (commandStatus) {
            case 0x00000058 -> true; // ESME_RTHROTTLED - throttling error
            case 0x00000014 -> true; // ESME_RMSGQFUL - message queue full
            case 0x00000008 -> true; // ESME_RSYSERR - system error
            case 0x00000400 -> true; // ESME_RSUBMITFAIL - submit failed (temporary)
            
            // Non-retryable errors (permanent failures)
            case 0x00000001, // ESME_RINVMSGLEN - invalid message length
                 0x00000002, // ESME_RINVCMDLEN - invalid command length
                 0x00000003, // ESME_RINVCMDID - invalid command ID
                 0x00000004, // ESME_RINVBNDSTS - invalid bind status
                 0x0000000A, // ESME_RINVDSTADR - invalid destination address
                 0x0000000B, // ESME_RINVDSTADDRTON - invalid dest addr TON
                 0x0000000C, // ESME_RINVDSTADDRNPI - invalid dest addr NPI
                 0x0000000E, // ESME_RINVSRCADR - invalid source address
                 0x0000000F, // ESME_RINVSRCADDRTON - invalid source addr TON
                 0x00000010, // ESME_RINVSRCADDRNPI - invalid source addr NPI
                 0x00000011, // ESME_RINVDSTTON - invalid destination TON
                 0x00000033, // ESME_RINVDLNAME - invalid DL name
                 0x00000045, // ESME_RINVNUMDESTS - invalid number of destinations
                 0x00000066 -> false; // ESME_RINVDATACODNG - invalid data coding
            
            default -> true; // Retry unknown errors by default
        };
    }
}
