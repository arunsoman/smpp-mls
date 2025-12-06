package com.cascade.smppmls.service;

import com.cascade.smppmls.entity.DelayedMessageLog;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.event.MessageExitEvent;
import com.cascade.smppmls.repository.DelayedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class DelayedMessageListener {

    private final DelayedMessageRepository repository;
    private static final long DELAY_THRESHOLD_SECONDS = 60;

    @EventListener
    public void handleMessageExit(MessageExitEvent event) {
        try {
            SmsOutboundEntity message = event.getMessage();
            Instant exitTime = Instant.now();
            Instant entryTime = message.getCreatedAt();

            if (entryTime == null) {
                log.warn("Message ID {} has no creation time, skipping delay check", message.getId());
                return;
            }

            long durationSeconds = Duration.between(entryTime, exitTime).getSeconds();

            if (durationSeconds > DELAY_THRESHOLD_SECONDS) {
                log.info("Message ID {} was in system for {} seconds (Threshold: {}s). Logging delay.", 
                        message.getId(), durationSeconds, DELAY_THRESHOLD_SECONDS);

                DelayedMessageLog logEntry = DelayedMessageLog.builder()
                        .originalMessageId(message.getId())
                        .msisdn(message.getMsisdn())
                        .entryTime(entryTime)
                        .exitTime(exitTime)
                        .durationSeconds(durationSeconds)
                        .status(event.isSuccess() ? "SENT" : "FAILED")
                        .reason(event.getReason())
                        .build();

                repository.save(logEntry);
            }
        } catch (Exception e) {
            log.error("Error processing MessageExitEvent: {}", e.getMessage(), e);
        }
    }
}
