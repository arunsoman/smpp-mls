package com.cascade.smppmls.service;

import com.cascade.smppmls.event.MessageDelayedEvent;
import com.cascade.smppmls.event.MessageSentEvent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final Map<Long, Alert> activeAlerts = new ConcurrentHashMap<>();
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 300000) // Run every 5 minutes
    public void cleanupExpiredAlerts() {
        try {
            Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
            int initialSize = activeAlerts.size();
            activeAlerts.entrySet().removeIf(entry -> entry.getValue().getTimestamp().isBefore(cutoff));
            int removed = initialSize - activeAlerts.size();
            if (removed > 0) {
                log.info("Alert cleanup: removed {} expired alerts, {} active remaining", removed, activeAlerts.size());
            }
        } catch (Exception e) {
            log.error("Error during alert cleanup", e);
        }
    }

    @Scheduled(fixedRate = 60000) // Update every minute
    public void updateAlertMetrics() {
        if (meterRegistry != null) {
            meterRegistry.gauge("smpp.alert.active.count", activeAlerts.size());
        }
    }

    @Data
    public static class Alert {
        private Long messageId;
        private String message;
        private String type; // "WARNING", "CRITICAL", etc.
        private Instant timestamp;
    }

    @EventListener
    public void handleMessageDelayed(MessageDelayedEvent event) {
        if (!activeAlerts.containsKey(event.getMessageId())) {
            Alert alert = new Alert();
            alert.setMessageId(event.getMessageId());
            alert.setMessage("Message ID " + event.getMessageId() + " to " + event.getMsisdn() + " is delayed (>1 min). Priority escalated to HIGH.");
            alert.setType("WARNING");
            alert.setTimestamp(Instant.now());
            
            activeAlerts.put(event.getMessageId(), alert);
            log.warn("Alert generated: {}", alert.getMessage());
        }
    }

    @EventListener
    public void handleMessageSent(MessageSentEvent event) {
        if (activeAlerts.containsKey(event.getMessageId())) {
            activeAlerts.remove(event.getMessageId());
            log.info("Alert cleared for message ID {}", event.getMessageId());
        }
    }

    public List<Alert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts.values());
    }
}
