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
                    // Determine base session key
                    String baseId = (session.getUuId() != null && !session.getUuId().isEmpty())
                        ? session.getUuId()
                        : operatorId + ":" + session.getSystemId();
                    
                    // Get session count (default to 1 if not specified)
                    int sessionCount = session.getSessionCount() > 0 ? session.getSessionCount() : 1;
                    
                    // Expand sessions based on sessionCount
                    if (sessionCount > 1) {
                        for (int i = 1; i <= sessionCount; i++) {
                            sessionIds.add(baseId + "-" + i);
                        }
                    } else {
                        sessionIds.add(baseId);
                    }
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
                
                // Get session using round-robin
                List<String> sessions = operatorSessions.get(operatorId);
                if (sessions != null && !sessions.isEmpty()) {
                    AtomicInteger counter = sessionRoundRobin.get(operatorId);
                    int index = Math.abs(counter.getAndIncrement() % sessions.size());
                    String sessionId = sessions.get(index);
                    
                    return new String[] { operatorId, sessionId };
                }
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
