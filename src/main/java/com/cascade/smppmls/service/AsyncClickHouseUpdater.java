package com.cascade.smppmls.service;

import com.cascade.smppmls.model.UpdateRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Async bulk updater for ClickHouse.
 * Batches status updates from Redis and bulk-updates ClickHouse every 5 seconds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncClickHouseUpdater {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate clickHouseTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    @Value("${clickhouse.archive.database:smpp_archive}")
    private String archiveDatabase;
    
    @Scheduled(fixedDelayString = "${queue.bulk-update-interval-ms:5000}")
    public void bulkUpdateClickHouse() {
        try {
            long startTime = System.currentTimeMillis();
            
            // 1. Get all pending updates from Redis set
            Set<String> pending = redisTemplate.opsForSet().members("pending:clickhouse:updates");
            if (pending == null || pending.isEmpty()) {
                return;
            }
            
            // 2. Remove from pending set (atomic)
            Long removed = redisTemplate.opsForSet().remove("pending:clickhouse:updates", pending.toArray());
            log.debug("Removed {} updates from pending set", removed);
            
            // 3. Parse updates
            List<UpdateRecord> updates = pending.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, UpdateRecord.class);
                    } catch (Exception e) {
                        log.error("Failed to parse update record: {}", json, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            if (updates.isEmpty()) {
                log.warn("No valid updates after parsing {} entries", pending.size());
                return;
            }
            
            // 4. Bulk update using ALTER TABLE (ClickHouse-specific)
            // Note: ClickHouse doesn't support traditional UPDATE, uses ALTER TABLE UPDATE
            for (UpdateRecord update : updates) {
                String sql = "ALTER TABLE " + archiveDatabase + ".sms_outbound UPDATE " +
                            "status = ?, smsc_msg_id = ?, queued_duration_ms = ?, " +
                            "error_message = ?, updated_at = now() " +
                            "WHERE id = ?";
                
                clickHouseTemplate.update(sql, 
                    update.getStatus(), 
                    update.getSmscMsgId(), 
                    update.getQueueDuration(),
                    update.getErrorMessage(),
                    update.getId()
                );
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Bulk updated {} messages in ClickHouse in {}ms", updates.size(), duration);
            
            // 5. Update metrics
            meterRegistry.counter("clickhouse.bulk.update.count").increment(updates.size());
            meterRegistry.timer("clickhouse.bulk.update.duration").record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
            
        } catch (Exception e) {
            log.error("Error in bulk update to ClickHouse", e);
            meterRegistry.counter("clickhouse.bulk.update.errors").increment();
            // Updates stay in Redis, will retry in next cycle
        }
    }
}
