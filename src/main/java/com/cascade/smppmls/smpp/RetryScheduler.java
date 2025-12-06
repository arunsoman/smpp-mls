package com.cascade.smppmls.smpp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;

/**
 * Per-operator retry scheduler with time-based eviction policy.
 * Processes retry queue partitioned by operator to ensure fair processing
 * and prevent one operator's backlog from starving others.
 * 
 * Messages are evicted (FAILED) if:
 * 1. They exceed the max retry attempts, OR
 * 2. They are older than the configured eviction hours (from creation time)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final SmsOutboundRepository outboundRepository;
    private final SmppProperties smppProperties;

    private static final int BATCH_SIZE_PER_OPERATOR = 50;

    /**
     * Run every 1 second, processing retry queue per-operator.
     * Each operator gets fair share of processing regardless of queue depth.
     */
    @Scheduled(fixedDelayString = "1000")
    public void retryReadyMessages() {
        // Get all operators from config
        if (smppProperties.getOperators() == null || smppProperties.getOperators().isEmpty()) {
            return;
        }

        for (String operatorId : smppProperties.getOperators().keySet()) {
            try {
                int processed = processRetryForOperator(operatorId);
                if (processed > 0) {
                    log.debug("Operator [{}]: processed {} retry messages", operatorId, processed);
                }
            } catch (Exception e) {
                log.error("Operator [{}]: retry processing error: {}", operatorId, e.getMessage());
            }
        }
    }

    /**
     * Process retry queue for a specific operator.
     * @return number of messages processed
     */
    private int processRetryForOperator(String operatorId) {
        SmppProperties.Retry retryConfig = smppProperties.getRetry();
        int maxRetries = retryConfig.getMaxAttempts();
        int evictionHours = retryConfig.getEvictionHours();
        Instant evictionCutoff = Instant.now().minus(evictionHours, ChronoUnit.HOURS);
        
        var page = outboundRepository.findByStatusAndOperatorAndNextRetryAtBefore(
            "RETRY", operatorId, Instant.now(), PageRequest.of(0, BATCH_SIZE_PER_OPERATOR));
        
        int processed = 0;
        int requeued = 0;
        int failedMaxRetries = 0;
        int evictedAge = 0;

        for (SmsOutboundEntity e : page) {
            // Check eviction by age first (message too old)
            if (e.getCreatedAt() != null && e.getCreatedAt().isBefore(evictionCutoff)) {
                e.setStatus("FAILED");
                outboundRepository.save(e);
                log.info("Operator [{}]: message id={} evicted (age > {} hours) -> FAILED", 
                    operatorId, e.getId(), evictionHours);
                evictedAge++;
            }
            // Check if max retries exceeded
            else if (e.getRetryCount() != null && e.getRetryCount() >= maxRetries) {
                e.setStatus("FAILED");
                outboundRepository.save(e);
                log.info("Operator [{}]: message id={} reached max retries ({}) -> FAILED", 
                    operatorId, e.getId(), maxRetries);
                failedMaxRetries++;
            } else {
                // Re-queue for another attempt
                e.setStatus("QUEUED");
                e.setNextRetryAt(null);
                outboundRepository.save(e);
                log.debug("Operator [{}]: re-queued message id={} for retry (count={})", 
                    operatorId, e.getId(), e.getRetryCount());
                requeued++;
            }
            processed++;
        }

        if (processed > 0) {
            log.info("Operator [{}]: retry batch complete - requeued={}, maxRetries={}, evicted={}", 
                operatorId, requeued, failedMaxRetries, evictedAge);
        }

        return processed;
    }

    /**
     * Get retry queue statistics per operator (for monitoring/dashboard).
     */
    public java.util.Map<String, Long> getRetryQueueStats() {
        java.util.Map<String, Long> stats = new java.util.LinkedHashMap<>();
        
        if (smppProperties.getOperators() != null) {
            for (String operatorId : smppProperties.getOperators().keySet()) {
                long count = outboundRepository.countByStatusAndOperator("RETRY", operatorId);
                stats.put(operatorId, count);
            }
        }
        
        return stats;
    }

    /**
     * Get retry configuration for monitoring.
     */
    public java.util.Map<String, Object> getRetryConfig() {
        SmppProperties.Retry retryConfig = smppProperties.getRetry();
        java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("maxAttempts", retryConfig.getMaxAttempts());
        config.put("baseDelayMs", retryConfig.getBaseDelayMs());
        config.put("maxDelayMs", retryConfig.getMaxDelayMs());
        config.put("evictionHours", retryConfig.getEvictionHours());
        return config;
    }
}
