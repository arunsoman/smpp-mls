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
        alertService.initMetrics(); // Register gauge for test
    }

    @Test
    void testCleanupExpiredAlerts() {
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
        
        // Run cleanup
        alertService.cleanupExpiredAlerts();
        
        // Verify
        List<AlertService.Alert> remaining = alertService.getActiveAlerts();
        assertEquals(1, remaining.size());
        assertEquals(100L, remaining.get(0).getMessageId());
        
        // Gauge auto-updates from activeAlerts.size()
        assertEquals(1.0, meterRegistry.get("smpp.alert.active.count").gauge().value());
    }
}
