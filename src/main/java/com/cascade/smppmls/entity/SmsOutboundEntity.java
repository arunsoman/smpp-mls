package com.cascade.smppmls.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sms_outbound")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmsOutboundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_msg_id", length = 64)
    private String clientMsgId;
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "smsc_msg_id", length = 64)
    private String smscMsgId;

    @Column(name = "msisdn", length = 20)
    private String msisdn;

    @Column(name = "source_addr", length = 20)
    private String sourceAddr;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "priority", length = 10)
    private String priority;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "session_id", length = 50)
    private String sessionId;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "encoding", length = 20)
    private String encoding;

    @Column(name = "udh", length = 255)
    private String udh;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "submit_sm_status")
    private Integer submitSmStatus;

    @Column(name = "submit_sm_error", length = 255)
    private String submitSmError;

    @Column(name = "submit_response_time_ms")
    private Long submitResponseTimeMs;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (retryCount == null) retryCount = 0;
    }
    
    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
