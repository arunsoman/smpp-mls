package com.cascade.smppmls.service;

import com.cascade.smppmls.event.MessageDelayedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertServiceTest {

    private AlertService alertService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alertService = new AlertService(meterRegistry);
    }

    @Test
    void testCleanupExpiredAlerts() {
        // 1. Add an old alert (simulated by hacking the timestamp via reflection or just relying on logic if possible)
        // Since Alert is a static inner class with private fields and no setters for timestamp in the service interface,
        // we rely on the fact that handleMessageDelayed sets timestamp to now().
        // To test expiration, we need to be able to set an old timestamp.
        // Let's rely on the internal map structure.
        
        // Actually, Alert @Data generates setters.
        
        // Add a fresh alert
        MessageDelayedEvent event1 = new MessageDelayedEvent(this, 100L, "123456", Instant.now());
        alertService.handleMessageDelayed(event1);
        
        // Add an old alert
        MessageDelayedEvent event2 = new MessageDelayedEvent(this, 101L, "987654", Instant.now());
        alertService.handleMessageDelayed(event2);
        
        // Manually set timestamp of alert 2 to be old
        List<AlertService.Alert> alerts = alertService.getActiveAlerts();
        AlertService.Alert oldAlert = alerts.stream().filter(a -> a.getMessageId().equals(101L)).findFirst().orElseThrow();
        oldAlert.setTimestamp(Instant.now().minus(2, ChronoUnit.HOURS));
        
        // 2. Run cleanup
        alertService.cleanupExpiredAlerts();
        
        // 3. Verify
        List<AlertService.Alert> remaining = alertService.getActiveAlerts();
        assertEquals(1, remaining.size());
        assertEquals(100L, remaining.get(0).getMessageId());
        
        // Verify metric
        alertService.updateAlertMetrics();
        assertEquals(1.0, meterRegistry.get("smpp.alert.active.count").gauge().value());
    }
}
