package com.cascade.smppmls.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResponse {
    private String requestId;
    private String messageId;
    private String status;
    private String operator;
    private String sessionId;
}
