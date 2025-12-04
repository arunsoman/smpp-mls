package com.cascade.smppmls.service;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.event.MessageDelayedEvent;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageMonitorService {

    private final SmsOutboundRepository outboundRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRate = 10000) // Run every 10 seconds
    @Transactional
    public void checkDelayedMessages() {
        Instant oneMinuteAgo = Instant.now().minus(1, ChronoUnit.MINUTES);
        
        // Find messages that are QUEUED, older than 1 minute, and NOT yet HIGH priority
        // We only check priority != HIGH to avoid repeatedly escalating the same message
        // However, we still want to alert if it's stuck even if already HIGH?
        // The requirement says: "immediately the priority of than message should be made high... and put into the delivery pipeline"
        // If we query priority != HIGH, we handle the escalation.
        // If we want to alert on ALREADY HIGH messages that are stuck, we'd need another check.
        // For now, let's focus on the escalation part which triggers the alert.
        
        List<SmsOutboundEntity> delayedMessages = outboundRepository.findByStatusAndCreatedAtBeforeAndPriorityNot(
                "QUEUED", oneMinuteAgo, "HIGH");

        for (SmsOutboundEntity message : delayedMessages) {
            log.info("Found delayed message ID {}. Escalating priority to HIGH.", message.getId());
            
            // 1. Escalate priority
            message.setPriority("HIGH");
            message.setUpdatedAt(Instant.now());
            outboundRepository.save(message);
            
            // 2. Publish event
            eventPublisher.publishEvent(new MessageDelayedEvent(
                    this, 
                    message.getId(), 
                    message.getMsisdn(), 
                    message.getCreatedAt()
            ));
        }
    }
}
