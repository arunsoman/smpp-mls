package com.cascade.smppmls.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.smpp.SmppSessionManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service to automatically reroute queued messages from stopped sessions
 * to active sessions within the same operator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRerouterService {

    private final SmsOutboundRepository outboundRepository;
    private final SmppSessionManager sessionManager;
    private final OperatorRouter operatorRouter;

    /**
     * Runs every 10 seconds to check for stopped sessions with queued messages
     * and reassigns them to active sessions
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 30000)
    @Transactional
    public void rerouteStoppedSessionMessages() {
        try {
            Map<String, Boolean> sessionHealth = sessionManager.getSessionHealth();
            
            // Find stopped sessions
            List<String> stoppedSessions = sessionHealth.entrySet().stream()
                .filter(entry -> !entry.getValue()) // isActive = false
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
            if (stoppedSessions.isEmpty()) {
                return; // No stopped sessions, nothing to do
            }
            
            log.debug("Checking {} stopped sessions for queued messages", stoppedSessions.size());
            
            int totalRerouted = 0;
            
            for (String stoppedSessionKey : stoppedSessions) {
                // Find queued messages for this stopped session
                List<SmsOutboundEntity> queuedMessages = outboundRepository
                    .findByStatusAndSessionId("QUEUED", stoppedSessionKey, 
                        org.springframework.data.domain.PageRequest.of(0, 1000))
                    .getContent();
                
                if (queuedMessages.isEmpty()) {
                    continue;
                }
                
                log.info("Found {} queued messages in stopped session: {}", 
                    queuedMessages.size(), stoppedSessionKey);
                
                // Group messages by operator
                Map<String, List<SmsOutboundEntity>> messagesByOperator = queuedMessages.stream()
                    .collect(Collectors.groupingBy(msg -> msg.getOperator() != null ? msg.getOperator() : "unknown"));
                
                for (Map.Entry<String, List<SmsOutboundEntity>> entry : messagesByOperator.entrySet()) {
                    String operator = entry.getKey();
                    List<SmsOutboundEntity> messages = entry.getValue();
                    
                    if ("unknown".equals(operator)) {
                        log.warn("Skipping {} messages with unknown operator from session {}", 
                            messages.size(), stoppedSessionKey);
                        continue;
                    }
                    
                    // Get active sessions for this operator
                    List<String> activeSessions = operatorRouter.getSessionsForOperator(operator).stream()
                        .filter(sessionHealth::get) // Only active sessions
                        .filter(sessionKey -> !sessionKey.equals(stoppedSessionKey)) // Exclude stopped session
                        .collect(Collectors.toList());
                    
                    if (activeSessions.isEmpty()) {
                        log.warn("No active sessions available for operator {} to reroute {} messages", 
                            operator, messages.size());
                        continue;
                    }
                    
                    log.info("Rerouting {} messages from {} to {} active sessions: {}", 
                        messages.size(), stoppedSessionKey, activeSessions.size(), activeSessions);
                    
                    // Reassign messages using round-robin
                    int sessionIndex = 0;
                    for (SmsOutboundEntity message : messages) {
                        String newSessionKey = activeSessions.get(sessionIndex % activeSessions.size());
                        message.setSessionId(newSessionKey);
                        sessionIndex++;
                    }
                    
                    // Batch save
                    outboundRepository.saveAll(messages);
                    totalRerouted += messages.size();
                    
                    log.info("Successfully rerouted {} messages from {} to active sessions", 
                        messages.size(), stoppedSessionKey);
                }
            }
            
            if (totalRerouted > 0) {
                log.info("Message rerouting completed: {} messages reassigned from stopped sessions", 
                    totalRerouted);
            }
            
        } catch (Exception e) {
            log.error("Error during message rerouting: {}", e.getMessage(), e);
        }
    }
}
