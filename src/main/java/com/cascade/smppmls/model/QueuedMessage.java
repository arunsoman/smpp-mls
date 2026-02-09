package com.cascade.smppmls.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Message model stored in Redis queue.
 * This is serialized as JSON and stored in Redis Sorted Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuedMessage implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Unique message ID (Snowflake ID or auto-generated)
     */
    private Long id;
    
    /**
     * Client-provided message ID for idempotency
     */
    private String clientMsgId;
    
    /**
     * Request ID for tracking
     */
    private String requestId;
    
    /**
     * Destination phone number (E.164 format)
     */
    private String msisdn;
    
    /**
     * SMS message content
     */
    private String message;
    
    /**
     * Source address (sender ID)
     */
    private String sourceAddr;
    
    /**
     * Message status (QUEUED, SENT, FAILED, TIMEOUT)
     */
    private String status;
    
    /**
     * Operator name (roshan, mtn, awcc, etc.)
     */
    private String operator;
    
    /**
     * Session ID for routing
     */
    private String sessionId;
    
    /**
     * Priority (HIGH, NORMAL)
     */
    @Builder.Default
    private String priority = "NORMAL";
    
    /**
     * Timestamp when message was queued (epoch millis)
     */
    private Long queuedAt;
    
    /**
     * Content signature for duplicate detection
     */
    private String signature;
}
