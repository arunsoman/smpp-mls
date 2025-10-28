package com.cascade.smppmls.api;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cascade.smppmls.repository.SmsOutboundRepository;

@RestController
@RequestMapping("/api/verify")
public class VerificationController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private SmsOutboundRepository outboundRepository;

    @GetMapping("/status")
    public Map<String, Object> getStatusSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // 1. Overall status by priority
        String sql = "SELECT priority, status, COUNT(*) as count FROM sms_outbound GROUP BY priority, status ORDER BY priority DESC, status";
        List<Map<String, Object>> statusBreakdown = jdbcTemplate.queryForList(sql);
        result.put("statusBreakdown", statusBreakdown);
        
        // Calculate totals
        long totalQueued = statusBreakdown.stream()
            .filter(row -> "QUEUED".equals(row.get("STATUS")))
            .mapToLong(row -> ((Number) row.get("COUNT")).longValue())
            .sum();
        
        long totalSent = statusBreakdown.stream()
            .filter(row -> "SENT".equals(row.get("STATUS")))
            .mapToLong(row -> ((Number) row.get("COUNT")).longValue())
            .sum();
        
        result.put("totalQueued", totalQueued);
        result.put("totalSent", totalSent);
        result.put("warning", totalQueued > 0 && totalSent == 0 ? "Messages queued but none sent - check SessionSender" : null);
        
        return result;
    }
    
    @GetMapping("/sessions")
    public Map<String, Object> getSessionDistribution() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        String sql = "SELECT session_id, priority, COUNT(*) as count FROM sms_outbound WHERE session_id IS NOT NULL GROUP BY session_id, priority ORDER BY session_id, priority DESC";
        List<Map<String, Object>> distribution = jdbcTemplate.queryForList(sql);
        result.put("sessionDistribution", distribution);
        
        return result;
    }
    
    @GetMapping("/priority")
    public Map<String, Object> getPriorityAnalysis() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // HP vs NP percentage
        String sql = "SELECT priority, COUNT(*) as count, " +
                     "ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM sms_outbound WHERE status = 'SENT'), 2) as percentage " +
                     "FROM sms_outbound WHERE status = 'SENT' GROUP BY priority";
        
        try {
            List<Map<String, Object>> priorityStats = jdbcTemplate.queryForList(sql);
            result.put("priorityPercentages", priorityStats);
            
            // Check HP percentage
            for (Map<String, Object> row : priorityStats) {
                if ("HIGH".equals(row.get("PRIORITY"))) {
                    Object pctObj = row.get("PERCENTAGE");
                    if (pctObj != null) {
                        double hpPct = ((Number) pctObj).doubleValue();
                        result.put("hpPercentage", hpPct);
                        result.put("hpThrottlingOk", hpPct <= 25);
                        result.put("hpWarning", hpPct > 25 ? "HP percentage exceeds 25% - throttling may not be working" : null);
                    }
                }
            }
        } catch (Exception e) {
            result.put("error", "No sent messages yet");
        }
        
        // First 20 sent messages to verify HP sent first
        String timelineSql = "SELECT id, msisdn, LEFT(message, 30) as message, priority, status, created_at " +
                            "FROM sms_outbound WHERE status IN ('SENT', 'DELIVERED') ORDER BY created_at ASC LIMIT 20";
        List<Map<String, Object>> timeline = jdbcTemplate.queryForList(timelineSql);
        result.put("firstSentMessages", timeline);
        
        return result;
    }
    
    @GetMapping("/routing")
    public Map<String, Object> getRoutingAnalysis() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        String sql = "SELECT " +
                     "CASE " +
                     "  WHEN msisdn LIKE '%9320%' OR msisdn LIKE '%9325%' THEN 'AFTEL' " +
                     "  WHEN msisdn LIKE '%9379%' OR msisdn LIKE '%9377%' OR msisdn LIKE '%9372%' THEN 'Roshan' " +
                     "  WHEN msisdn LIKE '%9370%' OR msisdn LIKE '%9371%' THEN 'AWCC' " +
                     "  WHEN msisdn LIKE '%9378%' OR msisdn LIKE '%9376%' THEN 'MTN' " +
                     "  WHEN msisdn LIKE '%9374%' OR msisdn LIKE '%9375%' THEN 'Salaam' " +
                     "  ELSE 'Unknown' " +
                     "END as operator, " +
                     "COUNT(*) as total, " +
                     "SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END) as sent, " +
                     "SUM(CASE WHEN priority = 'HIGH' THEN 1 ELSE 0 END) as hp_count, " +
                     "SUM(CASE WHEN priority = 'NORMAL' THEN 1 ELSE 0 END) as np_count " +
                     "FROM sms_outbound " +
                     "GROUP BY CASE " +
                     "  WHEN msisdn LIKE '%9320%' OR msisdn LIKE '%9325%' THEN 'AFTEL' " +
                     "  WHEN msisdn LIKE '%9379%' OR msisdn LIKE '%9377%' OR msisdn LIKE '%9372%' THEN 'Roshan' " +
                     "  WHEN msisdn LIKE '%9370%' OR msisdn LIKE '%9371%' THEN 'AWCC' " +
                     "  WHEN msisdn LIKE '%9378%' OR msisdn LIKE '%9376%' THEN 'MTN' " +
                     "  WHEN msisdn LIKE '%9374%' OR msisdn LIKE '%9375%' THEN 'Salaam' " +
                     "  ELSE 'Unknown' " +
                     "END " +
                     "ORDER BY total DESC";
        
        List<Map<String, Object>> routing = jdbcTemplate.queryForList(sql);
        result.put("operatorRouting", routing);
        
        return result;
    }
    
    @GetMapping("/all")
    public Map<String, Object> getFullVerification() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        result.put("status", getStatusSummary());
        result.put("sessions", getSessionDistribution());
        result.put("priority", getPriorityAnalysis());
        result.put("routing", getRoutingAnalysis());
        
        // Add retry analysis
        String retrySql = "SELECT retry_count, COUNT(*) as count FROM sms_outbound WHERE retry_count > 0 GROUP BY retry_count ORDER BY retry_count";
        List<Map<String, Object>> retries = jdbcTemplate.queryForList(retrySql);
        result.put("retries", retries.isEmpty() ? "No retries - all successful" : retries);
        
        return result;
    }
}
