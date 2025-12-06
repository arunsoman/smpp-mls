package com.cascade.smppmls.event;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class MessageExitEvent extends ApplicationEvent {

    private final SmsOutboundEntity message;
    private final boolean success;
    private final String reason;

    public MessageExitEvent(Object source, SmsOutboundEntity message, boolean success, String reason) {
        super(source);
        this.message = message;
        this.success = success;
        this.reason = reason;
    }
}
