package com.cascade.smppmls.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitRequest {

    @NotBlank
    private String msisdn;

    @NotBlank
    private String message;

    @NotBlank
    @Pattern(regexp = "HIGH|NORMAL", message = "priority must be HIGH or NORMAL")
    private String priority = "NORMAL";

    private String clientMsgId;

    private String encoding = "GSM7";

    private String udh;
}
