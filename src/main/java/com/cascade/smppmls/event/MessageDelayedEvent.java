package com.cascade.smppmls.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

@Getter
public class MessageDelayedEvent extends ApplicationEvent {
    private final Long messageId;
    private final String msisdn;
    private final Instant createdAt;

    public MessageDelayedEvent(Object source, Long messageId, String msisdn, Instant createdAt) {
        super(source);
        this.messageId = messageId;
        this.msisdn = msisdn;
        this.createdAt = createdAt;
    }
}
