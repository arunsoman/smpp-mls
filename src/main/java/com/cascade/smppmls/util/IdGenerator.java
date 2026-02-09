package com.cascade.smppmls.util;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Simple Snowflake-like ID generator.
 * Generates unique 64-bit IDs based on timestamp + sequence.
 */
@Component
public class IdGenerator {
    
    // Custom epoch (2024-01-01 00:00:00 UTC)
    private static final long CUSTOM_EPOCH = 1704067200000L;
    
    // Bit allocation
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    
    /**
     * Generate next unique ID.
     * Thread-safe.
     */
    public synchronized long nextId() {
        long timestamp = Instant.now().toEpochMilli();
        
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards!");
        }
        
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence overflow, wait for next millisecond
                timestamp = waitNextMillis(timestamp);
            }
        } else {
            sequence = 0;
        }
        
        lastTimestamp = timestamp;
        
        // Generate ID: timestamp (52 bits) + sequence (12 bits)
        return ((timestamp - CUSTOM_EPOCH) << SEQUENCE_BITS) | sequence;
    }
    
    private long waitNextMillis(long currentTimestamp) {
        long timestamp = Instant.now().toEpochMilli();
        while (timestamp <= currentTimestamp) {
            timestamp = Instant.now().toEpochMilli();
        }
        return timestamp;
    }
}
