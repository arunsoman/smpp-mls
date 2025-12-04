package com.cascade.smppmls.router;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;

@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorRouter {

    private final SmppProperties smppProperties;
    private final com.cascade.smppmls.smpp.JsmppSessionManager sessionManager;
    
    // Cache for normalized prefixes to avoid regex on every call
    private final Map<String, String> prefixToOperator = new ConcurrentHashMap<>();
    private final Map<String, List<String>> operatorSessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sessionRoundRobin = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing OperatorRouter with prefix caching...");
        
        Map<String, SmppProperties.Operator> ops = smppProperties.getOperators();
        if (ops == null || ops.isEmpty()) {
            log.warn("No operators configured for routing");
            return;
        }

        // Pre-compute all normalized prefixes and cache them
        for (Map.Entry<String, SmppProperties.Operator> entry : ops.entrySet()) {
            String operatorId = entry.getKey();
            SmppProperties.Operator op = entry.getValue();
            
            List<String> prefixes = op.getPrefixes();
            if (prefixes != null) {
                for (String prefix : prefixes) {
                    String normalizedPrefix = prefix.replaceAll("\\D", "");
                    if (!normalizedPrefix.isEmpty()) {
                        prefixToOperator.put(normalizedPrefix, operatorId);
                        log.debug("Mapped prefix {} -> operator {}", normalizedPrefix, operatorId);
                    }
                }
            }
            
            // Cache session IDs for round-robin
            if (op.getSessions() != null && !op.getSessions().isEmpty()) {
                List<String> sessionIds = new ArrayList<>();
                for (SmppProperties.Session session : op.getSessions()) {
                    // Use uuId if available, otherwise operatorId:systemId (must match JsmppSessionManager logic)
                    String sessionId = (session.getUuId() != null && !session.getUuId().isEmpty()) 
                        ? session.getUuId() 
                        : operatorId + ":" + session.getSystemId();
                    sessionIds.add(sessionId);
                }
                operatorSessions.put(operatorId, sessionIds);
                sessionRoundRobin.put(operatorId, new AtomicInteger(0));
                log.info("Operator {} has {} sessions: {}", operatorId, sessionIds.size(), sessionIds);
            }
        }
        
        log.info("OperatorRouter initialized with {} prefixes", prefixToOperator.size());
    }

    /**
     * Resolve operator id and a session ID for a normalized E.164 msisdn.
     * Returns String[]{operatorId, sessionId} or null if none found.
     * Uses round-robin load balancing across sessions.
     * Only selects sessions that are currently in bound state.
     */
    public String[] resolve(String e164Msisdn) {
        if (e164Msisdn == null) return null;
        
        // Fast path: remove non-digits once
        String digits = e164Msisdn.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        // Find matching prefix using cached map
        for (Map.Entry<String, String> entry : prefixToOperator.entrySet()) {
            String prefix = entry.getKey();
            if (digits.startsWith(prefix)) {
                String operatorId = entry.getValue();
                
                // Get all sessions for this operator
                List<String> allSessions = operatorSessions.get(operatorId);
                if (allSessions == null || allSessions.isEmpty()) {
                    continue;
                }
                
                // Filter to only bound sessions
                Map<String, Boolean> sessionHealth = sessionManager.getSessionHealth();
                List<String> boundSessions = new ArrayList<>();
                for (String sessionId : allSessions) {
                    Boolean isBound = sessionHealth.get(sessionId);
                    if (isBound != null && isBound) {
                        boundSessions.add(sessionId);
                    }
                }
                
                // If no bound sessions available, return null
                if (boundSessions.isEmpty()) {
                    log.warn("No bound sessions available for operator {} (prefix {})", operatorId, prefix);
                    return null;
                }
                
                // Use round-robin on bound sessions only
                AtomicInteger counter = sessionRoundRobin.get(operatorId);
                int index = Math.abs(counter.getAndIncrement() % boundSessions.size());
                String sessionId = boundSessions.get(index);
                
                return new String[] { operatorId, sessionId };
            }
        }
        
        return null;
    }
    
    /**
     * Get routing statistics
     */
    public Map<String, Object> getRoutingStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPrefixes", prefixToOperator.size());
        stats.put("totalOperators", operatorSessions.size());
        
        Map<String, Integer> sessionCounts = new LinkedHashMap<>();
        operatorSessions.forEach((op, sessions) -> sessionCounts.put(op, sessions.size()));
        stats.put("sessionsPerOperator", sessionCounts);
        
        return stats;
    }
}
