package com.cascade.smppmls.smpp;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;

@Component
public class RetryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RetryScheduler.class);

    private final SmsOutboundRepository outboundRepository;

    public RetryScheduler(SmsOutboundRepository outboundRepository) {
        this.outboundRepository = outboundRepository;
    }

    // run every 1s; pick a small batch to requeue
    @Scheduled(fixedDelayString = "1000")
    public void retryReadyMessages() {
        try {
            var page = outboundRepository.findByStatusAndNextRetryAtBefore("RETRY", Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));
            for (SmsOutboundEntity e : page) {
                // check retryCount limit - use system properties or defaults
                int max = 5;
                try { max = Integer.parseInt(System.getProperty("smpp.retry.max-attempts", "5")); } catch (Exception ex) {}
                if (e.getRetryCount() != null && e.getRetryCount() >= max) {
                    e.setStatus("FAILED");
                    outboundRepository.save(e);
                    logger.info("Message id={} reached max retries -> FAILED", e.getId());
                    continue;
                }

                // re-queue
                e.setStatus("QUEUED");
                e.setNextRetryAt(null);
                outboundRepository.save(e);
                logger.info("Re-queued message id={} for retry (count={})", e.getId(), e.getRetryCount());
            }
        } catch (Exception ex) {
            logger.error("RetryScheduler error: {}", ex.getMessage());
        }
    }
}
