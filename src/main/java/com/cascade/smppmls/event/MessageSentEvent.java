package com.cascade.smppmls.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class MessageSentEvent extends ApplicationEvent {
    private final Long messageId;

    public MessageSentEvent(Object source, Long messageId) {
        super(source);
        this.messageId = messageId;
    }
}
