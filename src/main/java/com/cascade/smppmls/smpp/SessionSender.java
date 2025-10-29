package com.cascade.smppmls.smpp;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;

import lombok.extern.slf4j.Slf4j;
import org.jsmpp.bean.*;
import org.jsmpp.session.SMPPSession;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.util.SmppAddressUtil;

@Slf4j
public class SessionSender implements Runnable {

    private final String sessionKey;
    private final SMPPSession session;
    private final int tps;
    private final int hpMaxPerSecond;
    private final SmsOutboundRepository outboundRepository;
    private final java.util.concurrent.ExecutorService submitExecutor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // runtime tokens
    private volatile double tokens;
    private volatile double hpTokens;

    private ScheduledFuture<?> future;

    public SessionSender(String sessionKey, SMPPSession session, int tps, int hpMaxPercentage, 
                         SmsOutboundRepository outboundRepository, 
                         java.util.concurrent.ExecutorService submitExecutor, 
                         io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.tps = Math.max(1, tps);
        this.hpMaxPerSecond = Math.max(0, (int) Math.ceil(this.tps * (hpMaxPercentage / 100.0)));
        this.outboundRepository = outboundRepository;
        this.submitExecutor = submitExecutor;
        this.meterRegistry = meterRegistry;
        this.tokens = this.tps; // start full
        this.hpTokens = this.hpMaxPerSecond;
        
        log.info("[{}] SessionSender initialized: TPS={}, HP_MAX={}", sessionKey, this.tps, this.hpMaxPerSecond);
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
            // refill tokens
            tokens = Math.min(tokens + tps, tps);
            hpTokens = Math.min(hpTokens + hpMaxPerSecond, hpMaxPerSecond);

            log.debug("[{}] Tick: tokens={}, hpTokens={}", sessionKey, tokens, hpTokens);

            // first send HP messages up to hpMaxPerSecond
            int toSendHp = (int)Math.floor(hpTokens);
            if (toSendHp > 0) {
                var page = outboundRepository.findByStatusAndSessionIdAndPriority("QUEUED", sessionKey, "HIGH", org.springframework.data.domain.PageRequest.of(0, toSendHp));
                long hpQueued = page.getTotalElements();
                log.debug("[{}] HP check: toSend={}, queued={}", sessionKey, toSendHp, hpQueued);
                
                int hpSent = 0;
                for (SmsOutboundEntity e : page) {
                    submitMessageAsync(e);
                    tokens = Math.max(0.0, tokens - 1.0);
                    hpTokens = Math.max(0.0, hpTokens - 1.0);
                    hpSent++;
                    if (tokens <= 0.0) break;
                }
                if (hpSent > 0) {
                    log.info("[{}] Submitted {} HP messages", sessionKey, hpSent);
                }
            }

            // then send NP messages with remaining tokens
            if (tokens > 0) {
                int npCount = (int)Math.floor(tokens);
                var page = outboundRepository.findByStatusAndSessionIdAndPriority("QUEUED", sessionKey, "NORMAL", org.springframework.data.domain.PageRequest.of(0, npCount));
                long npQueued = page.getTotalElements();
                log.debug("[{}] NP check: toSend={}, queued={}", sessionKey, npCount, npQueued);
                
                int npSent = 0;
                for (SmsOutboundEntity e : page) {
                    submitMessageAsync(e);
                    tokens = Math.max(0.0, tokens - 1.0);
                    npSent++;
                    if (tokens <= 0.0) break;
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
            try {
                // Determine proper TON/NPI for source and destination addresses
                String sourceAddress = (e.getSourceAddr() != null && !e.getSourceAddr().isEmpty()) 
                    ? e.getSourceAddr() : "";
                SmppAddressUtil.AddressInfo sourceInfo = SmppAddressUtil.getSourceAddressInfo(sourceAddress);
                SmppAddressUtil.AddressInfo destInfo = SmppAddressUtil.getDestinationAddressInfo(e.getMsisdn());
                
                log.debug("[{}] Submitting: src={} (TON={}, NPI={}), dest={} (TON={}, NPI={})",
                    sessionKey, sourceInfo.getAddress(), sourceInfo.getTon(), sourceInfo.getNpi(),
                    destInfo.getAddress(), destInfo.getTon(), destInfo.getNpi());
                
                String messageId = session.submitShortMessage(
                    "CMT",
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
                    new RegisteredDelivery(SMSCDeliveryReceipt.DEFAULT),
                    (byte)0,
                    new GeneralDataCoding(Alphabet.ALPHA_DEFAULT, MessageClass.CLASS1, false),
                    (byte)0,
                    e.getMessage().getBytes(StandardCharsets.UTF_8)
                ).getMessageId();

                if (messageId != null) {
                    String smscId = messageId;
                    e.setSmscMsgId(smscId);
                    e.setStatus("SENT");
                    outboundRepository.save(e);
                    log.info("[{}] Sent message id={} smsc_msg_id={} src={} dest={}", 
                        sessionKey, e.getId(), smscId, sourceInfo.getAddress(), destInfo.getAddress());
                    meterRegistry.counter("smpp.outbound.sent", "priority", e.getPriority(), "session", sessionKey).increment();
                }

            } catch (Exception se) {
                log.warn("[{}] submit exception id={} : {}", sessionKey, e.getId(), se.getMessage());
                try {
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
}
