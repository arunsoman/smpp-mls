package com.cascade.smppmls.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Record for bulk status updates to ClickHouse.
 * Stored in Redis set pending:clickhouse:updates
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRecord implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Message ID to update
     */
    private Long id;
    
    /**
     * New status (SENT, FAILED, TIMEOUT, DELIVERED)
     */
    private String status;
    
    /**
     * SMSC message ID (from submit response)
     */
    private String smscMsgId;
    
    /**
     * Time spent in queue (milliseconds)
     */
    private Long queueDuration;
    
    /**
     * Error message (if FAILED or TIMEOUT)
     */
    private String errorMessage;
    
    /**
     * Timestamp when update was created
     */
    @Builder.Default
    private Long updatedAt = System.currentTimeMillis();
}
