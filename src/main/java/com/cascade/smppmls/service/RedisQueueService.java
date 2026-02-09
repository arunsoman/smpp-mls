package com.cascade.smppmls.service;

import com.cascade.smppmls.model.QueuedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis queue service for managing message queues.
 * Uses Redis Sorted Sets for FIFO queue with timestamp-based ordering.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisQueueService {
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * Push message to queue for a specific session.
     * 
     * @param sessionId Session ID (e.g., "roshan:sess1")
     * @param message Message to queue
     */
    public void pushToQueue(String sessionId, QueuedMessage message) {
        try {
            String queueKey = "queue:" + sessionId;
            String msgJson = objectMapper.writeValueAsString(message);
            
            // Add to sorted set with timestamp as score (for FIFO + timeout detection)
            redisTemplate.opsForZSet().add(queueKey, msgJson, message.getQueuedAt().doubleValue());
            
            log.debug("Pushed message {} to queue {}", message.getId(), queueKey);
        } catch (Exception e) {
            log.error("Failed to push message {} to queue {}", message.getId(), sessionId, e);
            throw new RuntimeException("Failed to push to queue", e);
        }
    }
    
    /**
     * Add message to pending ClickHouse insert set.
     * 
     * @param message Message to add
     */
    public void addToPendingInsert(QueuedMessage message) {
        try {
            String msgJson = objectMapper.writeValueAsString(message);
            redisTemplate.opsForSet().add("pending:clickhouse", msgJson);
            
            log.debug("Added message {} to pending ClickHouse insert", message.getId());
        } catch (Exception e) {
            log.error("Failed to add message {} to pending insert", message.getId(), e);
            throw new RuntimeException("Failed to add to pending insert", e);
        }
    }
    
    /**
     * Pop next message from queue (FIFO).
     * 
     * @param sessionId Session ID
     * @return Message or null if queue is empty
     */
    public QueuedMessage popFromQueue(String sessionId) {
        try {
            String queueKey = "queue:" + sessionId;
            
            // Use Lua script for atomic ZPOPMIN compatibility (Redis < 5.0)
            String script = "local val = redis.call('zrange', KEYS[1], 0, 0)\n" +
                            "if val[1] then\n" +
                            "    redis.call('zrem', KEYS[1], val[1])\n" +
                            "    return val[1]\n" +
                            "end\n" +
                            "return nil";

            org.springframework.data.redis.core.script.DefaultRedisScript<String> redisScript = 
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, String.class);
                
            String msgJson = redisTemplate.execute(redisScript, java.util.Collections.singletonList(queueKey));
            
            if (msgJson == null) {
                return null;
            }
            
            QueuedMessage message = objectMapper.readValue(msgJson, QueuedMessage.class);
            
            log.debug("Popped message {} from queue {}", message.getId(), queueKey);
            return message;
            
        } catch (Exception e) {
            log.error("Failed to pop from queue {}", sessionId, e);
            return null;
        }
    }
    
    /**
     * Get queue size for a session.
     * 
     * @param sessionId Session ID
     * @return Queue size
     */
    public long getQueueSize(String sessionId) {
        String queueKey = "queue:" + sessionId;
        Long size = redisTemplate.opsForZSet().size(queueKey);
        return size != null ? size : 0;
    }
    
    /**
     * Find messages older than cutoff timestamp.
     * 
     * @param sessionId Session ID
     * @param cutoffTimestamp Cutoff timestamp (epoch millis)
     * @return Set of message JSONs
     */
    public Set<String> findStaleMessages(String sessionId, long cutoffTimestamp) {
        String queueKey = "queue:" + sessionId;
        return redisTemplate.opsForZSet().rangeByScore(queueKey, 0, cutoffTimestamp);
    }
    
    /**
     * Remove messages from queue.
     * 
     * @param sessionId Session ID
     * @param messages Messages to remove (JSON strings)
     * @return Number of messages removed
     */
    public long removeFromQueue(String sessionId, Set<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        
        String queueKey = "queue:" + sessionId;
        Long removed = redisTemplate.opsForZSet().remove(queueKey, messages.toArray());
        return removed != null ? removed : 0;
    }
    
    /**
     * Cache idempotency response.
     * 
     * @param clientMsgId Client message ID
     * @param messageId Message ID
     * @param ttlHours TTL in hours
     */
    public void cacheIdempotency(String clientMsgId, Long messageId, int ttlHours) {
        String cacheKey = "msg:" + clientMsgId;
        redisTemplate.opsForValue().set(cacheKey, String.valueOf(messageId), ttlHours, TimeUnit.HOURS);
    }
    
    /**
     * Get cached message ID for idempotency check.
     * 
     * @param clientMsgId Client message ID
     * @return Cached message ID or null
     */
    public Long getCachedMessageId(String clientMsgId) {
        String cacheKey = "msg:" + clientMsgId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        return cached != null ? Long.parseLong(cached) : null;
    }
}
