package com.cascade.smppmls.api;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cascade.smppmls.service.SubmissionService;

@RestController
@RequestMapping("/api")
public class SmsController {

    private static final Logger logger = LoggerFactory.getLogger(SmsController.class);

    private final SubmissionService submissionService;

    public SmsController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/v1/sms/submit")
    public ResponseEntity<SubmitResponse> submitSms(@Valid @RequestBody SubmitRequest req) {
        SubmitResponse resp = submissionService.submit(req);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/sms/send")
    public ResponseEntity<SubmitResponse> sendSms(@Valid @RequestBody SubmitRequest req) {
        SubmitResponse resp = submissionService.submit(req);
        return ResponseEntity.ok(resp);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}
