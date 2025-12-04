package com.cascade.smppmls.service;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.event.MessageDelayedEvent;
import com.cascade.smppmls.event.MessageSentEvent;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageAlertingTest {

    @Mock
    private SmsOutboundRepository outboundRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MessageMonitorService monitorService;
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        monitorService = new MessageMonitorService(outboundRepository, eventPublisher);
        alertService = new AlertService();
    }

    @Test
    void testMonitorServiceDetectsDelayedMessages() {
        // Arrange
        Instant now = Instant.now();
        Instant twoMinutesAgo = now.minus(2, ChronoUnit.MINUTES);
        
        SmsOutboundEntity delayedMessage = SmsOutboundEntity.builder()
                .id(100L)
                .msisdn("1234567890")
                .status("QUEUED")
                .priority("NORMAL")
                .createdAt(twoMinutesAgo)
                .build();

        when(outboundRepository.findByStatusAndCreatedAtBeforeAndPriorityNot(
                eq("QUEUED"), any(Instant.class), eq("HIGH")))
                .thenReturn(List.of(delayedMessage));

        // Act
        monitorService.checkDelayedMessages();

        // Assert
        // 1. Verify priority escalation
        ArgumentCaptor<SmsOutboundEntity> entityCaptor = ArgumentCaptor.forClass(SmsOutboundEntity.class);
        verify(outboundRepository).save(entityCaptor.capture());
        assertEquals("HIGH", entityCaptor.getValue().getPriority());
        assertNotNull(entityCaptor.getValue().getUpdatedAt());

        // 2. Verify event publication
        ArgumentCaptor<MessageDelayedEvent> eventCaptor = ArgumentCaptor.forClass(MessageDelayedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(100L, eventCaptor.getValue().getMessageId());
        assertEquals("1234567890", eventCaptor.getValue().getMsisdn());
    }

    @Test
    void testAlertServiceHandlesEvents() {
        // Arrange
        Long messageId = 200L;
        MessageDelayedEvent delayedEvent = new MessageDelayedEvent(this, messageId, "9876543210", Instant.now());

        // Act - Handle Delayed Event
        alertService.handleMessageDelayed(delayedEvent);

        // Assert - Alert Created
        List<AlertService.Alert> alerts = alertService.getActiveAlerts();
        assertEquals(1, alerts.size());
        assertEquals(messageId, alerts.get(0).getMessageId());
        assertEquals("WARNING", alerts.get(0).getType());
        assertTrue(alerts.get(0).getMessage().contains("delayed"));

        // Act - Handle Sent Event
        MessageSentEvent sentEvent = new MessageSentEvent(this, messageId);
        alertService.handleMessageSent(sentEvent);

        // Assert - Alert Removed
        alerts = alertService.getActiveAlerts();
        assertTrue(alerts.isEmpty());
    }
    
    @Test
    void testMonitorServiceDoesNothingWhenNoDelayedMessages() {
        // Arrange
        when(outboundRepository.findByStatusAndCreatedAtBeforeAndPriorityNot(
                eq("QUEUED"), any(Instant.class), eq("HIGH")))
                .thenReturn(List.of());

        // Act
        monitorService.checkDelayedMessages();

        // Assert
        verify(outboundRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
