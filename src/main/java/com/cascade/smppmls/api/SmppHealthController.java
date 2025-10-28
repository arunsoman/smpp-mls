package com.cascade.smppmls.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cascade.smppmls.smpp.SmppSessionManager;

@RestController
@RequestMapping("/api/smpp")
public class SmppHealthController {

    private final SmppSessionManager sessionManager;

    public SmppHealthController(@Qualifier("jsmppSessionManager") SmppSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> getSessionHealth() {
        Map<String, Boolean> health = sessionManager.getSessionHealth();
        return ResponseEntity.ok(health);
    }
}
