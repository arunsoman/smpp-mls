package com.cascade.smppmls.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import com.cascade.smppmls.smpp.SmppSessionManager;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.repository.SmsDlrRepository;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    @Autowired
    @Qualifier("jsmppSessionManager")
    private SmppSessionManager sessionManager;
    
    @Autowired
    private SmsOutboundRepository outboundRepository;
    
    @Autowired
    private SmsDlrRepository dlrRepository;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    // Track metrics in memory (in production, use Redis or metrics DB)
    private static final Map<String, SessionMetrics> sessionMetrics = new ConcurrentHashMap<>();
    
    /**
     * GET /api/admin/dashboard
     * Get complete dashboard data
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        
        try {
            // Overall statistics
            dashboard.put("overview", getOverviewStats().getBody());
            
            // Session status
            dashboard.put("sessions", getSessionStatus().getBody());
            
            // Throughput metrics
            dashboard.put("throughput", getThroughputMetrics().getBody());
            
            // Performance metrics
            dashboard.put("performance", getPerformanceMetrics().getBody());
            
            // Recent activity
            dashboard.put("recentActivity", getRecentActivity().getBody());
            
            // Alerts
            dashboard.put("alerts", getAlerts().getBody());
        } catch (Exception e) {
            // Return safe defaults on error
            dashboard.put("error", e.getMessage());
            dashboard.put("overview", Map.of("totalMessages", 0, "successRate", 0, "activeSessions", 0, "totalSessions", 0));
            dashboard.put("sessions", List.of());
            dashboard.put("throughput", Map.of("messagesPerMinute", List.of(), "currentTps", 0, "peakTps", 0, "byOperator", List.of()));
            dashboard.put("performance", Map.of("avgSubmissionDelayMs", 0, "deliverySuccessRate", 0, "avgDeliveryTimeMs", 0, "retryRate", 0));
            dashboard.put("recentActivity", List.of());
            dashboard.put("alerts", List.of());
        }
        
        return ResponseEntity.ok(dashboard);
    }
    
    /**
     * GET /api/admin/overview
     * Get overview statistics
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverviewStats() {
        Map<String, Object> overview = new LinkedHashMap<>();
        
        // Total messages
        long totalMessages = outboundRepository.count();
        overview.put("totalMessages", totalMessages);
        
        // Messages by status
        String statusSql = "SELECT status, COUNT(*) as count FROM sms_outbound GROUP BY status";
        List<Map<String, Object>> statusCounts = jdbcTemplate.queryForList(statusSql);
        
        Map<String, Long> byStatus = new HashMap<>();
        for (Map<String, Object> row : statusCounts) {
            String status = (String) (row.get("STATUS") != null ? row.get("STATUS") : row.get("status"));
            Number count = (Number) (row.get("COUNT") != null ? row.get("COUNT") : row.get("count"));
            if (status != null && count != null) {
                byStatus.put(status, count.longValue());
            }
        }
        overview.put("messagesByStatus", byStatus);
        
        // Success rate
        long sent = byStatus.getOrDefault("SENT", 0L);
        long delivered = byStatus.getOrDefault("DELIVERED", 0L);
        long failed = byStatus.getOrDefault("FAILED", 0L);
        long total = sent + delivered + failed;
        
        double successRate = total > 0 ? ((double)(sent + delivered) / total * 100) : 0;
        overview.put("successRate", Math.round(successRate * 100.0) / 100.0);
        
        // Active sessions
        Map<String, Boolean> sessionHealth = sessionManager.getSessionHealth();
        long activeSessions = sessionHealth.values().stream().filter(b -> b).count();
        overview.put("activeSessions", activeSessions);
        overview.put("totalSessions", sessionHealth.size());
        
        // Messages today
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        String todaySql = "SELECT COUNT(*) FROM sms_outbound WHERE created_at >= ?";
        Long messagesToday = jdbcTemplate.queryForObject(todaySql, Long.class, startOfToday);
        overview.put("messagesToday", messagesToday);
        
        // Messages last hour
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        String hourSql = "SELECT COUNT(*) FROM sms_outbound WHERE created_at >= ?";
        Long messagesLastHour = jdbcTemplate.queryForObject(hourSql, Long.class, oneHourAgo);
        overview.put("messagesLastHour", messagesLastHour);
        
        return ResponseEntity.ok(overview);
    }
    
    /**
     * GET /api/admin/sessions
     * Get detailed session status
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessionStatus() {
        Map<String, Boolean> sessionHealth = sessionManager.getSessionHealth();
        List<Map<String, Object>> sessions = new ArrayList<>();
        
        for (Map.Entry<String, Boolean> entry : sessionHealth.entrySet()) {
            String sessionKey = entry.getKey();
            boolean isActive = entry.getValue();
            
            Map<String, Object> sessionInfo = new LinkedHashMap<>();
            sessionInfo.put("sessionId", sessionKey);
            
            // Try to get detailed state if available
            String detailedStatus = "UNKNOWN";
            try {
                if (sessionManager instanceof com.cascade.smppmls.smpp.SocketSmppSessionManager) {
                    com.cascade.smppmls.smpp.SocketSmppSessionManager socketManager = 
                        (com.cascade.smppmls.smpp.SocketSmppSessionManager) sessionManager;
                    detailedStatus = socketManager.getSessionState(sessionKey).toString();
                } else if (sessionManager instanceof com.cascade.smppmls.smpp.JsmppSessionManager) {
                    com.cascade.smppmls.smpp.JsmppSessionManager jsmppManager = 
                        (com.cascade.smppmls.smpp.JsmppSessionManager) sessionManager;
                    detailedStatus = jsmppManager.getSessionState(sessionKey).toString();
                } else {
                    detailedStatus = isActive ? "CONNECTED" : "DISCONNECTED";
                }
            } catch (Exception e) {
                detailedStatus = isActive ? "CONNECTED" : "DISCONNECTED";
            }
            
            sessionInfo.put("status", detailedStatus);
            sessionInfo.put("isActive", isActive);
            
            // Parse operator and system ID
            String[] parts = sessionKey.split(":");
            if (parts.length == 2) {
                sessionInfo.put("operator", parts[0]);
                sessionInfo.put("systemId", parts[1]);
            }
            
            // Get session metrics
            String sql = "SELECT " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END) as sent, " +
                        "SUM(CASE WHEN status = 'QUEUED' THEN 1 ELSE 0 END) as queued, " +
                        "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed " +
                        "FROM sms_outbound WHERE session_id = ?";
            
            Map<String, Object> metrics = jdbcTemplate.queryForMap(sql, sessionKey);
            sessionInfo.put("metrics", metrics);
            
            // Get last activity
            String lastActivitySql = "SELECT MAX(created_at) FROM sms_outbound WHERE session_id = ?";
            try {
                Instant lastActivity = jdbcTemplate.queryForObject(lastActivitySql, Instant.class, sessionKey);
                sessionInfo.put("lastActivity", lastActivity);
            } catch (Exception e) {
                sessionInfo.put("lastActivity", null);
            }
            
            sessions.add(sessionInfo);
        }
        
        return ResponseEntity.ok(sessions);
    }
    
    /**
     * GET /api/admin/throughput
     * Get throughput metrics
     */
    @GetMapping("/throughput")
    public ResponseEntity<Map<String, Object>> getThroughputMetrics() {
        Map<String, Object> throughput = new LinkedHashMap<>();
        
        // Messages per minute (last 10 minutes) - database agnostic
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        String minuteSql = 
            "SELECT FLOOR(UNIX_TIMESTAMP(created_at) / 60) as minute_bucket, " +
            "COUNT(*) as message_count " +
            "FROM sms_outbound " +
            "WHERE created_at >= ? " +
            "GROUP BY FLOOR(UNIX_TIMESTAMP(created_at) / 60) " +
            "ORDER BY minute_bucket DESC " +
            "LIMIT 10";
        
        List<Map<String, Object>> perMinute = jdbcTemplate.queryForList(minuteSql, tenMinutesAgo);
        // Normalize keys for frontend
        List<Map<String, Object>> normalizedPerMinute = new ArrayList<>();
        for (Map<String, Object> row : perMinute) {
            Map<String, Object> normalized = new HashMap<>();
            Object minute = row.get("MINUTE_BUCKET") != null ? row.get("MINUTE_BUCKET") : row.get("minute_bucket");
            Object count = row.get("MESSAGE_COUNT") != null ? row.get("MESSAGE_COUNT") : row.get("message_count");
            if (minute != null) normalized.put("MINUTE", minute);
            if (count != null) normalized.put("COUNT", count);
            normalizedPerMinute.add(normalized);
        }
        throughput.put("messagesPerMinute", normalizedPerMinute);
        
        // Current TPS (messages in last minute / 60)
        Instant sixtySecondsAgo = Instant.now().minus(60, ChronoUnit.SECONDS);
        String tpsSql = "SELECT COUNT(*) FROM sms_outbound WHERE created_at >= ?";
        Long messagesLastMinute = jdbcTemplate.queryForObject(tpsSql, Long.class, sixtySecondsAgo);
        double currentTps = messagesLastMinute != null ? messagesLastMinute / 60.0 : 0;
        throughput.put("currentTps", Math.round(currentTps * 100.0) / 100.0);
        
        // Peak TPS (highest minute in last hour) - database agnostic
        Instant oneHourAgo2 = Instant.now().minus(1, ChronoUnit.HOURS);
        String peakSql = 
            "SELECT MAX(cnt) as peak FROM (" +
            "  SELECT COUNT(*) as cnt " +
            "  FROM sms_outbound " +
            "  WHERE created_at >= ? " +
            "  GROUP BY FLOOR(UNIX_TIMESTAMP(created_at) / 60)" +
            ") subquery";
        
        try {
            Long peakPerMinute = jdbcTemplate.queryForObject(peakSql, Long.class, oneHourAgo2);
            double peakTps = peakPerMinute != null ? peakPerMinute / 60.0 : 0;
            throughput.put("peakTps", Math.round(peakTps * 100.0) / 100.0);
        } catch (Exception e) {
            throughput.put("peakTps", 0);
        }
        
        // Throughput by operator
        Instant oneHourAgo3 = Instant.now().minus(1, ChronoUnit.HOURS);
        String operatorSql = 
            "SELECT operator, COUNT(*) as count " +
            "FROM sms_outbound " +
            "WHERE created_at >= ? " +
            "GROUP BY operator";
        
        List<Map<String, Object>> byOperator = jdbcTemplate.queryForList(operatorSql, oneHourAgo3);
        throughput.put("byOperator", byOperator);
        
        return ResponseEntity.ok(throughput);
    }
    
    /**
     * GET /api/admin/performance/operators
     * Get per-operator performance metrics with trends
     */
    @GetMapping("/performance/operators")
    public ResponseEntity<Map<String, Object>> getOperatorPerformance() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // Get list of operators
        String operatorsSql = "SELECT DISTINCT operator FROM sms_outbound WHERE operator IS NOT NULL";
        List<String> operators = jdbcTemplate.queryForList(operatorsSql, String.class);
        
        List<Map<String, Object>> operatorMetrics = new ArrayList<>();
        
        for (String operator : operators) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("operator", operator);
            
            // Success Rate - Last 1 hour
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            String successRate1hSql = 
                "SELECT " +
                "SUM(CASE WHEN status = 'SENT' OR status LIKE '%DELIVR%' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0) as rate " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND created_at >= ?";
            Double successRate1h = jdbcTemplate.queryForObject(successRate1hSql, Double.class, operator, oneHourAgo);
            metrics.put("successRate1h", successRate1h != null ? Math.round(successRate1h * 100.0) / 100.0 : 0);
            
            // Success Rate - Last 5 minutes
            Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
            String successRate5mSql = 
                "SELECT " +
                "SUM(CASE WHEN status = 'SENT' OR status LIKE '%DELIVR%' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0) as rate " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND created_at >= ?";
            Double successRate5m = jdbcTemplate.queryForObject(successRate5mSql, Double.class, operator, fiveMinutesAgo);
            metrics.put("successRate5m", successRate5m != null ? Math.round(successRate5m * 100.0) / 100.0 : 0);
            
            // Retry Rate - Last 1 hour
            String retryRate1hSql = 
                "SELECT " +
                "SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0) as rate " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND created_at >= ?";
            Double retryRate1h = jdbcTemplate.queryForObject(retryRate1hSql, Double.class, operator, oneHourAgo);
            metrics.put("retryRate1h", retryRate1h != null ? Math.round(retryRate1h * 100.0) / 100.0 : 0);
            
            // Retry Rate - Last 5 minutes
            String retryRate5mSql = 
                "SELECT " +
                "SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0) as rate " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND created_at >= ?";
            Double retryRate5m = jdbcTemplate.queryForObject(retryRate5mSql, Double.class, operator, fiveMinutesAgo);
            metrics.put("retryRate5m", retryRate5m != null ? Math.round(retryRate5m * 100.0) / 100.0 : 0);
            
            // Submit Delay - Last 1 hour (time from created to sent)
            String submitDelay1hSql = 
                "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) / 1000.0) as avg_delay " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND status = 'SENT' AND created_at >= ?";
            Double submitDelay1h = jdbcTemplate.queryForObject(submitDelay1hSql, Double.class, operator, oneHourAgo);
            metrics.put("submitDelay1h", submitDelay1h != null ? Math.round(submitDelay1h) : 0);
            
            // Submit Delay - Last 5 minutes
            String submitDelay5mSql = 
                "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) / 1000.0) as avg_delay " +
                "FROM sms_outbound " +
                "WHERE operator = ? AND status = 'SENT' AND created_at >= ?";
            Double submitDelay5m = jdbcTemplate.queryForObject(submitDelay5mSql, Double.class, operator, fiveMinutesAgo);
            metrics.put("submitDelay5m", submitDelay5m != null ? Math.round(submitDelay5m) : 0);
            
            // DR Delay - Last 1 hour (time from sent to DR received)
            String drDelay1hSql = 
                "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, o.updated_at, d.received_at) / 1000.0) as avg_delay " +
                "FROM sms_dlr d " +
                "JOIN sms_outbound o ON d.sms_outbound_id = o.id " +
                "WHERE o.operator = ? AND d.received_at >= ?";
            Double drDelay1h = jdbcTemplate.queryForObject(drDelay1hSql, Double.class, operator, oneHourAgo);
            metrics.put("drDelay1h", drDelay1h != null ? Math.round(drDelay1h) : 0);
            
            // DR Delay - Last 5 minutes
            String drDelay5mSql = 
                "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, o.updated_at, d.received_at) / 1000.0) as avg_delay " +
                "FROM sms_dlr d " +
                "JOIN sms_outbound o ON d.sms_outbound_id = o.id " +
                "WHERE o.operator = ? AND d.received_at >= ?";
            Double drDelay5m = jdbcTemplate.queryForObject(drDelay5mSql, Double.class, operator, fiveMinutesAgo);
            metrics.put("drDelay5m", drDelay5m != null ? Math.round(drDelay5m) : 0);
            
            operatorMetrics.add(metrics);
        }
        
        result.put("operators", operatorMetrics);
        return ResponseEntity.ok(result);
    }
    
    /**
     * GET /api/admin/performance
     * Get performance metrics (kept for backward compatibility)
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        Map<String, Object> performance = new LinkedHashMap<>();
        
        // Average submission delay (time from received to sent)
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        String avgDelaySql = 
            "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) / 1000.0) as avg_delay " +
            "FROM sms_outbound " +
            "WHERE status = 'SENT' AND created_at >= ?";
        
        Double avgDelay = jdbcTemplate.queryForObject(avgDelaySql, Double.class, oneHourAgo);
        performance.put("avgSubmissionDelayMs", avgDelay != null ? Math.round(avgDelay) : 0);
        
        // Delivery success rate
        String deliverySql = 
            "SELECT " +
            "SUM(CASE WHEN status LIKE '%DELIVR%' THEN 1 ELSE 0 END) as delivered, " +
            "COUNT(*) as total " +
            "FROM sms_dlr " +
            "WHERE received_at >= ?";
        
        try {
            Map<String, Object> deliveryStats = jdbcTemplate.queryForMap(deliverySql, oneHourAgo);
            Object deliveredObj = deliveryStats.get("DELIVERED");
            Object totalObj = deliveryStats.get("TOTAL");
            long delivered = deliveredObj != null ? ((Number) deliveredObj).longValue() : 0;
            long totalDlr = totalObj != null ? ((Number) totalObj).longValue() : 0;
            double deliveryRate = totalDlr > 0 ? (double) delivered / totalDlr * 100 : 0;
            performance.put("deliverySuccessRate", Math.round(deliveryRate * 100.0) / 100.0);
        } catch (Exception e) {
            performance.put("deliverySuccessRate", 0);
        }
        
        // Average delivery time
        String avgDeliveryTimeSql = 
            "SELECT AVG(TIMESTAMPDIFF(MICROSECOND, o.created_at, d.received_at) / 1000.0) as avg_time " +
            "FROM sms_dlr d " +
            "JOIN sms_outbound o ON d.sms_outbound_id = o.id " +
            "WHERE d.received_at >= ?";
        
        Double avgDeliveryTime = jdbcTemplate.queryForObject(avgDeliveryTimeSql, Double.class, oneHourAgo);
        performance.put("avgDeliveryTimeMs", avgDeliveryTime != null ? Math.round(avgDeliveryTime) : 0);
        
        // Retry rate
        String retrySql = 
            "SELECT " +
            "SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) as retried, " +
            "COUNT(*) as total " +
            "FROM sms_outbound " +
            "WHERE created_at >= ?";
        
        try {
            Map<String, Object> retryStats = jdbcTemplate.queryForMap(retrySql, oneHourAgo);
            Object retriedObj = retryStats.get("RETRIED") != null ? retryStats.get("RETRIED") : retryStats.get("retried");
            Object totalObj = retryStats.get("TOTAL") != null ? retryStats.get("TOTAL") : retryStats.get("total");
            long retried = retriedObj != null ? ((Number) retriedObj).longValue() : 0;
            long totalRetry = totalObj != null ? ((Number) totalObj).longValue() : 0;
            double retryRate = totalRetry > 0 ? (double) retried / totalRetry * 100 : 0;
            performance.put("retryRate", Math.round(retryRate * 100.0) / 100.0);
        } catch (Exception e) {
            performance.put("retryRate", 0);
        }
        
        return ResponseEntity.ok(performance);
    }
    
    /**
     * GET /api/admin/activity
     * Get recent activity
     */
    @GetMapping("/activity")
    public ResponseEntity<List<Map<String, Object>>> getRecentActivity() {
        String sql = 
            "SELECT id, msisdn, priority, status, operator, session_id, created_at, updated_at " +
            "FROM sms_outbound " +
            "ORDER BY created_at DESC " +
            "LIMIT 50";
        
        List<Map<String, Object>> activity = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(activity);
    }
    
    /**
     * GET /api/admin/alerts
     * Get system alerts
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        
        // Check for disconnected sessions
        Map<String, Boolean> sessionHealth = sessionManager.getSessionHealth();
        long disconnected = sessionHealth.values().stream().filter(b -> !b).count();
        
        if (disconnected > 0) {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("severity", "WARNING");
            alert.put("type", "SESSION_DISCONNECTED");
            alert.put("message", disconnected + " session(s) disconnected");
            alert.put("count", disconnected);
            alert.put("timestamp", Instant.now());
            alerts.add(alert);
        }
        
        // Check for high queue depth
        String queueSql = "SELECT COUNT(*) FROM sms_outbound WHERE status = 'QUEUED'";
        Long queueDepth = jdbcTemplate.queryForObject(queueSql, Long.class);
        
        if (queueDepth != null && queueDepth > 100) {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("severity", "WARNING");
            alert.put("type", "HIGH_QUEUE_DEPTH");
            alert.put("message", queueDepth + " messages queued");
            alert.put("count", queueDepth);
            alert.put("timestamp", Instant.now());
            alerts.add(alert);
        }
        
        // Check for high retry rate
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        String retryRateSql = 
            "SELECT " +
            "SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0) as retry_rate " +
            "FROM sms_outbound " +
            "WHERE created_at >= ?";
        
        Double retryRate = jdbcTemplate.queryForObject(retryRateSql, Double.class, fiveMinutesAgo);
        
        if (retryRate != null && retryRate > 10) {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("severity", "ERROR");
            alert.put("type", "HIGH_RETRY_RATE");
            alert.put("message", "Retry rate is " + Math.round(retryRate) + "%");
            alert.put("value", Math.round(retryRate * 100.0) / 100.0);
            alert.put("timestamp", Instant.now());
            alerts.add(alert);
        }
        
        return ResponseEntity.ok(alerts);
    }
    
    /**
     * POST /api/admin/session/{sessionId}/stop
     * Stop a specific session
     */
    @PostMapping("/session/{sessionId}/stop")
    public ResponseEntity<Map<String, Object>> stopSession(@PathVariable String sessionId) {
        Map<String, Object> response = new LinkedHashMap<>();
        
        try {
            sessionManager.stopSession(sessionId);
            response.put("success", true);
            response.put("message", "Session stopped successfully");
            response.put("sessionId", sessionId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to stop session: " + e.getMessage());
            response.put("sessionId", sessionId);
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * POST /api/admin/session/{sessionId}/start
     * Start a specific session
     */
    @PostMapping("/session/{sessionId}/start")
    public ResponseEntity<Map<String, Object>> startSession(@PathVariable String sessionId) {
        Map<String, Object> response = new LinkedHashMap<>();
        
        try {
            sessionManager.startSession(sessionId);
            response.put("success", true);
            response.put("message", "Session started successfully");
            response.put("sessionId", sessionId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to start session: " + e.getMessage());
            response.put("sessionId", sessionId);
            return ResponseEntity.status(500).body(response);
        }
    }
    
    // Helper class for session metrics
    static class SessionMetrics {
        long messagesSent;
        long messagesQueued;
        long messagesFailed;
        Instant lastActivity;
    }
}
