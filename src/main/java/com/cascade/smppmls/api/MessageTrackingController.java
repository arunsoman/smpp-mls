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
        submission.put("submittedAt", msg.getSentAt());
        
        // Submit_SM Response Tracking
        if (msg.getSubmitResponseTimeMs() != null) {
            submission.put("responseTimeMs", msg.getSubmitResponseTimeMs());
        }
        
        if (msg.getSubmitSmStatus() != null) {
            submission.put("submitSmStatus", msg.getSubmitSmStatus());
            submission.put("submitSmStatusHex", String.format("0x%08X", msg.getSubmitSmStatus()));
            
            // Add human-readable status
            String statusDescription = getSubmitSmStatusDescription(msg.getSubmitSmStatus());
            submission.put("submitSmStatusDescription", statusDescription);
        }
        
        if (msg.getSubmitSmError() != null) {
            submission.put("submitSmError", msg.getSubmitSmError());
        }
        
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
    
    /**
     * Get human-readable description for SMPP submit_sm status codes
     */
    private String getSubmitSmStatusDescription(int status) {
        return switch (status) {
            case 0x00000000 -> "ESME_ROK - Success";
            case 0x00000001 -> "ESME_RINVMSGLEN - Invalid message length";
            case 0x00000002 -> "ESME_RINVCMDLEN - Invalid command length";
            case 0x00000003 -> "ESME_RINVCMDID - Invalid command ID";
            case 0x00000004 -> "ESME_RINVBNDSTS - Invalid bind status";            
            case 0x00000005 -> "ESME_RALYBND - Already bound";
            case 0x00000006 -> "ESME_RINVPRTFLG - Invalid priority flag";
            case 0x00000007 -> "ESME_RINVREGDLVFLG - Invalid registered delivery flag";
            case 0x00000008 -> "ESME_RSYSERR - System error";
            case 0x0000000A -> "ESME_RINVDSTADR - Invalid destination address";
            case 0x0000000B -> "ESME_RINVDSTADDRTON - Invalid destination address TON";
            case 0x0000000C -> "ESME_RINVDSTADDRNPI - Invalid destination address NPI";
            case 0x0000000E -> "ESME_RINVSRCADR - Invalid source address";
            case 0x0000000F -> "ESME_RINVSRCADDRTON - Invalid source address TON";
            case 0x00000010 -> "ESME_RINVSRCADDRNPI - Invalid source address NPI";
            case 0x00000011 -> "ESME_RINVDSTTON - Invalid destination TON";
            case 0x00000013 -> "ESME_RINVDSTADDRSUBUNIT - Invalid destination address subunit";
            case 0x00000014 -> "ESME_RMSGQFUL - Message queue full";
            case 0x00000015 -> "ESME_RINVSERTYP - Invalid service type";
            case 0x00000033 -> "ESME_RINVDLNAME - Invalid distribution list name";
            case 0x00000034 -> "ESME_RINVDESTFLAG - Invalid destination flag";
            case 0x00000040 -> "ESME_RINVSUBREP - Invalid submit with replace";
            case 0x00000042 -> "ESME_RINVESMCLASS - Invalid ESM class";
            case 0x00000043 -> "ESME_RCNTSUBDL - Cannot submit to distribution list";
            case 0x00000044 -> "ESME_RSUBMITFAIL - Submit failed";
            case 0x00000045 -> "ESME_RINVNUMDESTS - Invalid number of destinations";
            case 0x00000048 -> "ESME_RINVDLMEMBTYP - Invalid distribution list member type";
            case 0x00000051 -> "ESME_RINVDLMEMBDESC - Invalid distribution list member description";
            case 0x00000058 -> "ESME_RTHROTTLED - Throttling error (submit message rate exceeded)";
            case 0x00000061 -> "ESME_RINVSCHED - Invalid scheduled delivery time";
            case 0x00000062 -> "ESME_RINVEXPIRY - Invalid validity period";
            case 0x00000063 -> "ESME_RINVDFTMSGID - Invalid predefined message ID";
            case 0x00000064 -> "ESME_RX_T_APPN - ESME receiver temporary error";
            case 0x00000065 -> "ESME_RX_P_APPN - ESME receiver permanent error";
            case 0x00000066 -> "ESME_RINVDATACODNG - Invalid data coding scheme";
            case 0x00000067 -> "ESME_RINVSRCADDRSUBUNIT - Invalid source address subunit";
            case 0x00000068 -> "ESME_RINVDSTADDRSUBUNIT - Invalid destination address subunit";
            case 0x000000C0 -> "ESME_RINVTLVSTREAM - Invalid TLV stream";
            case 0x000000C1 -> "ESME_RTLVNOTALLWD - TLV not allowed";
            case 0x000000C2 -> "ESME_RINVTLVLEN - Invalid TLV length";
            case 0x000000C3 -> "ESME_RMISSINGTLV - Missing TLV";
            case 0x000000C4 -> "ESME_RINVTLVVAL - Invalid TLV value";
            case 0x000000FE -> "ESME_RDELIVERYFAILURE - Delivery failure";
            case 0x000000FF -> "ESME_RUNKNOWNERR - Unknown error";
            case 0x00000100 -> "ESME_RSERTYPUNAUTH - Service type unauthorized";
            case 0x00000101 -> "ESME_RPROHIBITED - Prohibited";
            case 0x00000102 -> "ESME_RSERTYPUNAVAIL - Service type unavailable";
            case 0x00000103 -> "ESME_RSERTYPDENIED - Service type denied";
            case 0x00000400 -> "ESME_RSUBMITFAIL - Submit failed (temporary)";
            default -> String.format("Unknown SMPP error code: 0x%08X", status);
        };
    }
}
