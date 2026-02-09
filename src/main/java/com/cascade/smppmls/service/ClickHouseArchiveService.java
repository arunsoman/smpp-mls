package com.cascade.smppmls.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "clickhouse.archive.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ClickHouseArchiveService {

    private final JdbcTemplate h2JdbcTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    
    @Value("${clickhouse.url}")
    private String clickhouseUrl;
    
    @Value("${clickhouse.username}")
    private String clickhouseUsername;
    
    @Value("${clickhouse.password}")
    private String clickhousePassword;
    
    @Value("${clickhouse.archive.age-minutes:35}")
    private int archiveAgeMinutes;

    @Value("${clickhouse.archive.database:smpp_archive}")
    private String archiveDatabase;

    @PostConstruct
    public void initSchema() {
        try (Connection conn = getClickHouseConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // Create database
                stmt.execute("CREATE DATABASE IF NOT EXISTS " + archiveDatabase);
                
                // Create sms_outbound table
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS " + archiveDatabase + ".sms_outbound (" +
                    "    id UInt64," +
                    "    request_id String," +
                    "    client_msg_id Nullable(String)," +
                    "    msisdn String," +
                    "    message String," +
                    "    signature Nullable(String)," +
                    "    priority String," +
                    "    operator Nullable(String)," +
                    "    session_id Nullable(String)," +
                    "    status String," +
                    "    smsc_msg_id Nullable(String)," +
                    "    source_addr Nullable(String)," +
                    "    retry_count UInt32 DEFAULT 0," +
                    "    next_retry_at Nullable(DateTime64(3))," +
                    "    last_attempt_at Nullable(DateTime64(3))," +
                    "    submit_sm_status Nullable(Int32)," +
                    "    submit_sm_error Nullable(String)," +
                    "    submit_response_time_ms Nullable(UInt32)," +
                    "    queued_duration_ms Nullable(UInt32)," +
                    "    error_message Nullable(String)," +
                    "    created_at DateTime64(3)," +
                    "    updated_at Nullable(DateTime64(3))," +
                    "    sent_at Nullable(DateTime64(3))," +
                    "    archived_at DateTime64(3) DEFAULT now64(3)" +
                    ")" +
                    "ENGINE = MergeTree() " +
                    "PARTITION BY toYYYYMM(created_at) " +
                    "ORDER BY (created_at, id) " +
                    "TTL created_at + INTERVAL 90 DAY"
                );

                // Create sms_dlr table
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS " + archiveDatabase + ".sms_dlr (" +
                    "    id UInt64," +
                    "    sms_outbound_id UInt64," +
                    "    smsc_msg_id String," +
                    "    stat String," +
                    "    err Nullable(String)," +
                    "    text Nullable(String)," +
                    "    received_at DateTime64(3)," +
                    "    archived_at DateTime64(3) DEFAULT now64(3)," +
                    "    INDEX idx_outbound_id sms_outbound_id TYPE minmax GRANULARITY 1," +
                    "    INDEX idx_smsc_msg_id smsc_msg_id TYPE bloom_filter GRANULARITY 1" +
                    ")" +
                    "ENGINE = MergeTree() " +
                    "PARTITION BY toYYYYMM(received_at) " +
                    "ORDER BY (received_at, id) " +
                    "TTL received_at + INTERVAL 90 DAY"
                );
                
                log.info("ClickHouse schema initialized in database '{}'", archiveDatabase);
            }
        } catch (Exception e) {
            log.error("Failed to initialize ClickHouse schema: {}", e.getMessage());
            // Don't fail startup, just log error. Archiving will fail later if not fixed.
        }
    }

    /**
     * Archive old data every 3 hours by default
     */
    @Scheduled(cron = "${clickhouse.archive.schedule-cron:0 0 */3 * * ?}")
    public void archiveOldData() {
        log.info("Starting ClickHouse archive job...");
        long startTime = System.currentTimeMillis();
        
        try {
            int archivedOutbound = archiveSmsOutbound();
            int archivedDlr = archiveSmsDlr();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Archive completed: {} sms_outbound, {} sms_dlr archived in {}ms", 
                archivedOutbound, archivedDlr, duration);
            
            // Track metrics
            meterRegistry.counter("clickhouse.archived", "table", "sms_outbound").increment(archivedOutbound);
            meterRegistry.counter("clickhouse.archived", "table", "sms_dlr").increment(archivedDlr);
            meterRegistry.timer("clickhouse.archive.duration").record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
            
            // Track H2 table size
            try {
                Long tableSize = h2JdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_outbound", Long.class);
                if (tableSize != null) {
                    meterRegistry.gauge("h2.table.size", java.util.Collections.singletonList(io.micrometer.core.instrument.Tag.of("table", "sms_outbound")), tableSize);
                }
            } catch (Exception e) {
                log.warn("Failed to track H2 table size metric: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Archive job failed: {}", e.getMessage(), e);
        }
    }

    private int archiveSmsOutbound() throws Exception {
        Instant cutoff = Instant.now().minus(archiveAgeMinutes, ChronoUnit.MINUTES);
        
        // Read from H2 in batches (no locks - read uncommitted via separate connection or just simple select)
        // H2's MVCC should handle readers not blocking writers.
        // Only archive terminal states (SENT, FAILED) - not QUEUED messages still being processed
        String selectSql = 
            "SELECT id, request_id, client_msg_id, msisdn, message, signature, " +
            "priority, operator, session_id, status, smsc_msg_id, source_addr, " +
            "retry_count, next_retry_at, last_attempt_at, submit_sm_status, " +
            "submit_sm_error, submit_response_time_ms, submit_response_time_ms as queued_duration_ms, " +
            "submit_sm_error as error_message, created_at, updated_at, sent_at " +
            "FROM sms_outbound " +
            "WHERE status IN ('SENT', 'FAILED') AND created_at < ? " +
            "ORDER BY id " +
            "LIMIT 10000";
        
        String insertSql = 
            "INSERT INTO " + archiveDatabase + ".sms_outbound " +
            "(id, request_id, client_msg_id, msisdn, message, signature, " +
            "priority, operator, session_id, status, smsc_msg_id, source_addr, " +
            "retry_count, next_retry_at, last_attempt_at, submit_sm_status, " +
            "submit_sm_error, submit_response_time_ms, queued_duration_ms, error_message, " +
            "created_at, updated_at, sent_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        int totalArchived = 0;
        
        try (Connection clickHouseConn = getClickHouseConnection()) {
            // ClickHouse JDBC doesn't support manual commit/rollback in typical way, 
            // but batch execution works. 
            // setAutoCommit(false) is supported for batching.
            clickHouseConn.setAutoCommit(false);
            
            while (true) {
                List<Map<String, Object>> records = h2JdbcTemplate.queryForList(selectSql, cutoff);
                
                if (records.isEmpty()) {
                    break;
                }
                
                try (PreparedStatement pstmt = clickHouseConn.prepareStatement(insertSql)) {
                    for (Map<String, Object> record : records) {
                        pstmt.setLong(1, numberToLong(record.get("ID")));
                        pstmt.setString(2, (String) record.get("REQUEST_ID"));
                        pstmt.setString(3, (String) record.get("CLIENT_MSG_ID"));
                        pstmt.setString(4, (String) record.get("MSISDN"));
                        pstmt.setString(5, (String) record.get("MESSAGE"));
                        pstmt.setString(6, (String) record.get("SIGNATURE"));
                        pstmt.setString(7, (String) record.get("PRIORITY"));
                        pstmt.setString(8, (String) record.get("OPERATOR"));
                        pstmt.setString(9, (String) record.get("SESSION_ID"));
                        pstmt.setString(10, (String) record.get("STATUS"));
                        pstmt.setString(11, (String) record.get("SMSC_MSG_ID"));
                        pstmt.setString(12, (String) record.get("SOURCE_ADDR"));
                        pstmt.setInt(13, numberToInt(record.get("RETRY_COUNT")));
                        pstmt.setObject(14, record.get("NEXT_RETRY_AT"));
                        pstmt.setObject(15, record.get("LAST_ATTEMPT_AT"));
                        pstmt.setObject(16, record.get("SUBMIT_SM_STATUS"));
                        pstmt.setString(17, (String) record.get("SUBMIT_SM_ERROR"));
                        pstmt.setObject(18, record.get("SUBMIT_RESPONSE_TIME_MS"));
                        pstmt.setObject(19, record.get("QUEUED_DURATION_MS"));
                        pstmt.setString(20, (String) record.get("ERROR_MESSAGE"));
                        pstmt.setObject(21, record.get("CREATED_AT"));
                        pstmt.setObject(22, record.get("UPDATED_AT"));
                        pstmt.setObject(23, record.get("SENT_AT"));
                        pstmt.addBatch();
                    }
                    
                    pstmt.executeBatch();
                    clickHouseConn.commit();
                }
                
                // Delete from H2
                String deleteSql = "DELETE FROM sms_outbound WHERE created_at < ? LIMIT 10000";
                int deleted = h2JdbcTemplate.update(deleteSql, cutoff);
                totalArchived += deleted;
                
                log.debug("Archived batch: {} sms_outbound records", deleted);
                
                if (records.size() < 10000) {
                    break;
                }
            }
        }
        
        return totalArchived;
    }

    private int archiveSmsDlr() throws Exception {
        Instant cutoff = Instant.now().minus(archiveAgeMinutes, ChronoUnit.MINUTES);
        
        String selectSql = 
            "SELECT id, sms_outbound_id, smsc_msg_id, stat, err, text, received_at " +
            "FROM sms_dlr " +
            "WHERE received_at < ? " +
            "ORDER BY id " +
            "LIMIT 10000";
        
        String insertSql = 
            "INSERT INTO " + archiveDatabase + ".sms_dlr " +
            "(id, sms_outbound_id, smsc_msg_id, stat, err, text, received_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        int totalArchived = 0;
        
        try (Connection clickHouseConn = getClickHouseConnection()) {
            clickHouseConn.setAutoCommit(false);
            
            while (true) {
                List<Map<String, Object>> records = h2JdbcTemplate.queryForList(selectSql, cutoff);
                
                if (records.isEmpty()) {
                    break;
                }
                
                try (PreparedStatement pstmt = clickHouseConn.prepareStatement(insertSql)) {
                    for (Map<String, Object> record : records) {
                        pstmt.setLong(1, numberToLong(record.get("ID")));
                        pstmt.setLong(2, numberToLong(record.get("SMS_OUTBOUND_ID")));
                        pstmt.setString(3, (String) record.get("SMSC_MSG_ID"));
                        pstmt.setString(4, (String) record.get("STAT"));
                        pstmt.setString(5, (String) record.get("ERR"));
                        pstmt.setString(6, (String) record.get("TEXT"));
                        pstmt.setObject(7, record.get("RECEIVED_AT"));
                        pstmt.addBatch();
                    }
                    
                    pstmt.executeBatch();
                    clickHouseConn.commit();
                }
                
                String deleteSql = "DELETE FROM sms_dlr WHERE received_at < ? LIMIT 10000";
                int deleted = h2JdbcTemplate.update(deleteSql, cutoff);
                totalArchived += deleted;
                
                if (records.size() < 10000) {
                    break;
                }
            }
        }
        
        return totalArchived;
    }
    
    private Long numberToLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        return Long.valueOf(obj.toString());
    }
    
    private Integer numberToInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        return Integer.valueOf(obj.toString());
    }

    private Connection getClickHouseConnection() throws Exception {
        return java.sql.DriverManager.getConnection(
            clickhouseUrl, clickhouseUsername, clickhousePassword);
    }
}
