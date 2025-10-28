package com.cascade.smppmls.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.cascade.smppmls.model.OutboundMessage;

@Component
public class InMemoryOutboundStore {

    private final Map<String, OutboundMessage> store = new ConcurrentHashMap<>();

    public String save(OutboundMessage msg) {
        String requestId = msg.getRequestId();
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            msg.setRequestId(requestId);
        }
        store.put(requestId, msg);
        return requestId;
    }

    public Optional<OutboundMessage> findByRequestId(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }

}
