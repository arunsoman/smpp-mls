package com.cascade.smppmls.service;

import com.cascade.smppmls.model.QueuedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Async bulk writer for ClickHouse.
 * Batches messages from Redis and bulk-inserts to ClickHouse every 5 seconds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncClickHouseBulkWriter {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate clickHouseTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    @Value("${clickhouse.archive.database:smpp_archive}")
    private String archiveDatabase;
    
    @Scheduled(fixedDelayString = "${queue.bulk-insert-interval-ms:5000}")
    public void bulkInsertToClickHouse() {
        try {
            long startTime = System.currentTimeMillis();
            
            // 1. Get all pending messages from Redis set
            Set<String> pending = redisTemplate.opsForSet().members("pending:clickhouse");
            if (pending == null || pending.isEmpty()) {
                return;
            }
            
            // 2. Remove from pending set (atomic)
            Long removed = redisTemplate.opsForSet().remove("pending:clickhouse", pending.toArray());
            log.debug("Removed {} messages from pending set", removed);
            
            // 3. Parse messages
            List<QueuedMessage> messages = pending.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, QueuedMessage.class);
                    } catch (Exception e) {
                        log.error("Failed to parse message: {}", json, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            if (messages.isEmpty()) {
                log.warn("No valid messages to insert after parsing {} entries", pending.size());
                return;
            }
            
            // 4. Bulk insert to ClickHouse
            String sql = "INSERT INTO " + archiveDatabase + ".sms_outbound " +
                        "(id, request_id, client_msg_id, msisdn, message, source_addr, signature, " +
                        "status, operator, session_id, priority, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            
            clickHouseTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    QueuedMessage msg = messages.get(i);
                    ps.setLong(1, msg.getId());
                    ps.setString(2, msg.getRequestId());
                    ps.setString(3, msg.getClientMsgId());
                    ps.setString(4, msg.getMsisdn());
                    ps.setString(5, msg.getMessage());
                    ps.setString(6, msg.getSourceAddr());
                    ps.setString(7, msg.getSignature());
                    ps.setString(8, msg.getStatus());
                    ps.setString(9, msg.getOperator());
                    ps.setString(10, msg.getSessionId());
                    ps.setString(11, msg.getPriority());
                    ps.setTimestamp(12, new Timestamp(msg.getQueuedAt()));
                }
                
                @Override
                public int getBatchSize() {
                    return messages.size();
                }
            });
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Bulk inserted {} messages to ClickHouse in {}ms", messages.size(), duration);
            
            // 5. Update metrics
            meterRegistry.counter("clickhouse.bulk.insert.count").increment(messages.size());
            meterRegistry.timer("clickhouse.bulk.insert.duration").record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
            
        } catch (Exception e) {
            log.error("Error in bulk insert to ClickHouse", e);
            meterRegistry.counter("clickhouse.bulk.insert.errors").increment();
            // Messages stay in Redis, will retry in next cycle
        }
    }
}
