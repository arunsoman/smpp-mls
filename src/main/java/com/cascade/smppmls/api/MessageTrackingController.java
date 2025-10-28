package com.cascade.smppmls.api;

import java.time.Instant;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.entity.SmsDlrEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.repository.SmsDlrRepository;

@RestController
@RequestMapping("/api/track")
public class MessageTrackingController {

    @Autowired
    private SmsOutboundRepository outboundRepository;
    
    @Autowired
    private SmsDlrRepository dlrRepository;

    /**
     * Track message by ID
     * GET /api/track/message/{id}
     */
    @GetMapping("/message/{id}")
    public ResponseEntity<?> trackByMessageId(@PathVariable Long id) {
        Optional<SmsOutboundEntity> outbound = outboundRepository.findById(id);
        
        if (outbound.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Message not found",
                "messageId", id
            ));
        }
        
        return ResponseEntity.ok(buildMessageReport(outbound.get()));
    }
    
    /**
     * Track message by request ID
     * GET /api/track/request/{requestId}
     */
    @GetMapping("/request/{requestId}")
    public ResponseEntity<?> trackByRequestId(@PathVariable String requestId) {
        SmsOutboundEntity outbound = outboundRepository.findByRequestId(requestId);
        
        if (outbound == null) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Message not found",
                "requestId", requestId
            ));
        }
        
        return ResponseEntity.ok(buildMessageReport(outbound));
    }
    
    /**
     * Track message by SMSC message ID
     * GET /api/track/smsc/{smscMsgId}
     */
    @GetMapping("/smsc/{smscMsgId}")
    public ResponseEntity<?> trackBySmscMsgId(@PathVariable String smscMsgId) {
        SmsOutboundEntity outbound = outboundRepository.findBySmscMsgId(smscMsgId);
        
        if (outbound == null) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Message not found",
                "smscMsgId", smscMsgId
            ));
        }
        
        return ResponseEntity.ok(buildMessageReport(outbound));
    }
    
    /**
     * Track messages by phone number (MSISDN)
     * GET /api/track/phone/{msisdn}
     */
    @GetMapping("/phone/{msisdn}")
    public ResponseEntity<?> trackByPhone(@PathVariable String msisdn) {
        // Normalize phone number (add + if missing)
        String normalizedMsisdn = msisdn.startsWith("+") ? msisdn : "+" + msisdn;
        
        List<SmsOutboundEntity> messages = outboundRepository.findByMsisdn(normalizedMsisdn);
        
        if (messages.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "No messages found for this phone number",
                "msisdn", normalizedMsisdn
            ));
        }
        
        List<Map<String, Object>> reports = new ArrayList<>();
        for (SmsOutboundEntity msg : messages) {
            reports.add(buildMessageReport(msg));
        }
        
        return ResponseEntity.ok(Map.of(
            "msisdn", normalizedMsisdn,
            "totalMessages", messages.size(),
            "messages", reports
        ));
    }
    
    /**
     * Track messages by client message ID
     * GET /api/track/client/{clientMsgId}
     */
    @GetMapping("/client/{clientMsgId}")
    public ResponseEntity<?> trackByClientMsgId(@PathVariable String clientMsgId) {
        SmsOutboundEntity outbound = outboundRepository.findByClientMsgId(clientMsgId);
        
        if (outbound == null) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Message not found",
                "clientMsgId", clientMsgId
            ));
        }
        
        return ResponseEntity.ok(buildMessageReport(outbound));
    }
    
    /**
     * Build comprehensive message report
     */
    private Map<String, Object> buildMessageReport(SmsOutboundEntity msg) {
        Map<String, Object> report = new LinkedHashMap<>();
        
        // Basic Information
        report.put("messageId", msg.getId());
        report.put("requestId", msg.getRequestId());
        report.put("clientMsgId", msg.getClientMsgId());
        report.put("msisdn", msg.getMsisdn());
        report.put("message", msg.getMessage());
        report.put("priority", msg.getPriority());
        
        // Routing Information
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("operator", msg.getOperator());
        routing.put("sessionId", msg.getSessionId());
        report.put("routing", routing);
        
        // Status & Timing
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("currentStatus", msg.getStatus());
        status.put("receivedAt", msg.getCreatedAt());
        status.put("lastUpdatedAt", msg.getUpdatedAt());
        
        // Calculate time in system
        if (msg.getCreatedAt() != null) {
            Instant now = Instant.now();
            long secondsInSystem = java.time.Duration.between(msg.getCreatedAt(), now).getSeconds();
            status.put("timeInSystemSeconds", secondsInSystem);
        }
        
        report.put("status", status);
        
        // SMPP Submission Details
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("smscMessageId", msg.getSmscMsgId());
        submission.put("submittedAt", msg.getCreatedAt());
        
        // Calculate submission delay
        if (msg.getCreatedAt() != null && msg.getUpdatedAt() != null && "SENT".equals(msg.getStatus())) {
            long submissionDelayMs = java.time.Duration.between(msg.getCreatedAt(), msg.getUpdatedAt()).toMillis();
            submission.put("submissionDelayMs", submissionDelayMs);
        }
        
        report.put("submission", submission);
        
        // Retry Information
        if (msg.getRetryCount() != null && msg.getRetryCount() > 0) {
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("retryCount", msg.getRetryCount());
            retry.put("lastAttemptAt", msg.getLastAttemptAt());
            retry.put("nextRetryAt", msg.getNextRetryAt());
            report.put("retry", retry);
        }
        
        // Delivery Receipt (DLR) Information
        List<SmsDlrEntity> dlrs = dlrRepository.findBySmsOutboundId(msg.getId());
        if (!dlrs.isEmpty()) {
            List<Map<String, Object>> dlrList = new ArrayList<>();
            
            for (SmsDlrEntity dlr : dlrs) {
                Map<String, Object> dlrInfo = new LinkedHashMap<>();
                dlrInfo.put("dlrId", dlr.getId());
                dlrInfo.put("status", dlr.getStatus());
                dlrInfo.put("receivedAt", dlr.getReceivedAt());
                
                // Calculate delivery time
                if (msg.getCreatedAt() != null && dlr.getReceivedAt() != null) {
                    long deliveryTimeMs = java.time.Duration.between(msg.getCreatedAt(), dlr.getReceivedAt()).toMillis();
                    dlrInfo.put("deliveryTimeMs", deliveryTimeMs);
                    dlrInfo.put("deliveryTimeSeconds", deliveryTimeMs / 1000.0);
                }
                
                dlrList.add(dlrInfo);
            }
            
            report.put("deliveryReceipts", dlrList);
            
            // Add latest DLR status
            SmsDlrEntity latestDlr = dlrs.get(dlrs.size() - 1);
            report.put("deliveryStatus", latestDlr.getStatus());
            report.put("deliveredAt", latestDlr.getReceivedAt());
        } else {
            report.put("deliveryStatus", "PENDING");
            report.put("deliveryReceipts", Collections.emptyList());
        }
        
        // Timeline Summary
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("1_received", msg.getCreatedAt());
        
        if ("SENT".equals(msg.getStatus()) || "DELIVERED".equals(msg.getStatus())) {
            timeline.put("2_submitted", msg.getUpdatedAt());
        }
        
        if (!dlrs.isEmpty()) {
            timeline.put("3_delivered", dlrs.get(dlrs.size() - 1).getReceivedAt());
        }
        
        report.put("timeline", timeline);
        
        return report;
    }
}
