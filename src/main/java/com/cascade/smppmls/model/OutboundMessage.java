package com.cascade.smppmls.model;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundMessage {
    private String requestId;
    private String clientMsgId;
    private String smscMsgId;
    private String msisdn;
    private String message;
    private String priority;
    private String operator;
    private String sessionId;
    private String status;
    @Builder.Default
    private Instant createdAt = Instant.now();
}
