package com.cascade.smppmls.service;

import com.cascade.smppmls.model.QueuedMessage;
import com.cascade.smppmls.model.UpdateRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stale message cleaner - removes messages stuck in queue >1 minute.
 * Marks them as TIMEOUT in ClickHouse for audit trail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleMessageCleaner {
    
    private final RedisQueueService redisQueueService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    @Value("${queue.stale-message-timeout-seconds:60}")
    private int timeoutSeconds;
    
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void cleanStaleMessages() {
        try {
            long cutoff = System.currentTimeMillis() - (timeoutSeconds * 1000L);
            
            // Get all queue keys (queue:*)
            Set<String> queueKeys = redisTemplate.keys("queue:*");
            if (queueKeys == null || queueKeys.isEmpty()) {
                return;
            }
            
            int totalStale = 0;
            
            for (String queueKey : queueKeys) {
                String sessionId = queueKey.substring("queue:".length());
                
                // Find messages older than cutoff
                Set<String> staleJsons = redisQueueService.findStaleMessages(sessionId, cutoff);
                
                if (!staleJsons.isEmpty()) {
                    log.warn("[{}] Found {} stale messages (>{}s in queue)", 
                        sessionId, staleJsons.size(), timeoutSeconds);
                    
                    // Parse messages
                    List<QueuedMessage> staleMessages = staleJsons.stream()
                        .map(json -> {
                            try {
                                return objectMapper.readValue(json, QueuedMessage.class);
                            } catch (Exception e) {
                                log.error("Failed to parse stale message: {}", json, e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                    
                    // Remove from queue
                    long removed = redisQueueService.removeFromQueue(sessionId, staleJsons);
                    log.info("[{}] Removed {} stale messages from queue", sessionId, removed);
                    
                    // Add to ClickHouse update queue with TIMEOUT status
                    for (QueuedMessage msg : staleMessages) {
                        long queueDuration = System.currentTimeMillis() - msg.getQueuedAt();
                        
                        UpdateRecord update = UpdateRecord.builder()
                            .id(msg.getId())
                            .status("TIMEOUT")
                            .errorMessage(String.format("Queue timeout (>%ds)", timeoutSeconds))
                            .queueDuration(queueDuration)
                            .build();
                        
                        String updateJson = objectMapper.writeValueAsString(update);
                        redisTemplate.opsForSet().add("pending:clickhouse:updates", updateJson);
                    }
                    
                    totalStale += staleMessages.size();
                    
                    // Update metrics
                    meterRegistry.counter("smpp.queue.timeout", "session", sessionId)
                        .increment(staleMessages.size());
                }
            }
            
            if (totalStale > 0) {
                log.warn("Cleaned {} total stale messages across all sessions", totalStale);
            }
            
        } catch (Exception e) {
            log.error("Error in stale message cleaner", e);
            meterRegistry.counter("smpp.queue.timeout.errors").increment();
        }
    }
}
