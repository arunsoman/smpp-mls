package com.cascade.smppmls.smpp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsmpp.PDUStringException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.*;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.*;
import org.jsmpp.util.MessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * jSMPP-based SMPP session manager.
 */
@Component
public class JsmppSessionManager implements SmppSessionManager, MessageReceiverListener {

    private static final Logger logger = LoggerFactory.getLogger(JsmppSessionManager.class);

    // Session states (same as SocketSmppSessionManager)
    public enum SessionState {
        STOPPED, STARTING, CONNECTED, RETRYING, STOPPING
    }
    
    private final SmppProperties smppProperties;
    private final SmsOutboundRepository outboundRepository;
    private final Map<String, org.jsmpp.session.SMPPSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService senderScheduler = Executors.newScheduledThreadPool(8);
    private final ExecutorService submitExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, SessionSender> sessionSenders = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> senderFutures = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToKeyMap = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, Boolean> shouldRetry = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${priority.high.max-tps-percentage:20}")
    private int hpMaxPercentage;

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final com.cascade.smppmls.repository.SmsDlrRepository dlrRepository;

    public JsmppSessionManager(SmppProperties smppProperties, 
                             SmsOutboundRepository outboundRepository,
                             com.cascade.smppmls.repository.SmsDlrRepository dlrRepository,
                             io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.smppProperties = smppProperties;
        this.outboundRepository = outboundRepository;
        this.dlrRepository = dlrRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        start();
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    @Override
    public Map<String, Boolean> getSessionHealth() {
        Map<String, Boolean> health = new ConcurrentHashMap<>();
        
        // Include all sessions that have a state (even if stopped)
        sessionStates.keySet().forEach(sessionKey -> {
            SMPPSession session = sessions.get(sessionKey);
            boolean isHealthy = session != null && session.getSessionState().isBound();
            health.put(sessionKey, isHealthy);
        });
        
        return health;
    }

    @Override
    public void start() {
        if (smppProperties.getOperators() == null || smppProperties.getOperators().isEmpty()) {
            logger.warn("No SMPP operators configured for jSMPP manager");
            return;
        }

        // Log configuration
        int enquireLinkIntervalMs = smppProperties.getDefaultConfig().getEnquireLinkInterval();
        int enquireLinkIntervalSec = enquireLinkIntervalMs / 1000;
        logger.info("SMPP Configuration: enquire-link-interval={}ms ({}s), reconnect-delay={}ms", 
            enquireLinkIntervalMs, enquireLinkIntervalSec, smppProperties.getDefaultConfig().getReconnectDelay());

        smppProperties.getOperators().forEach((operatorId, operator) -> {
            if (operator.getSessions() == null) return;
            operator.getSessions().forEach(sessionCfg -> {
                String sessionKey = operatorId + ":" + sessionCfg.getSystemId();
                sessionStates.put(sessionKey, SessionState.STARTING);
                shouldRetry.put(sessionKey, true); // Auto-start sessions should retry
                // Start a dedicated bind loop for this session to handle reconnect/backoff
                Executors.newSingleThreadExecutor().execute(() -> bindLoop(sessionKey, operatorId, sessionCfg, operator.getHost(), operator.getPort()));
            });
        });
    }

    private void bindLoop(String sessionKey, String operatorId, SmppProperties.Session sessionCfg, String host, int port) {
        long backoff = Math.max(1000, smppProperties.getDefaultConfig().getReconnectDelay());
        final long maxBackoff = 60_000;
        
        while (shouldRetry.getOrDefault(sessionKey, false)) {
            org.jsmpp.session.SMPPSession session = null;
            try {
                logger.info("[{}] Attempting bind to {}:{}", sessionKey, host, port);
                
                // Create and configure the session
                session = new org.jsmpp.session.SMPPSession();
                session.setEnquireLinkTimer(smppProperties.getDefaultConfig().getEnquireLinkInterval() / 1000); // Convert to seconds
                session.setTransactionTimer(10000); // 10 seconds transaction timeout
                
                // Set up message receiver
                session.setMessageReceiverListener(this);
                
                // Connect and bind
                String systemId = sessionCfg.getSystemId();
                String password = sessionCfg.getPassword();
                String systemType = smppProperties.getDefaultConfig().getSystemType();
                
                session.connectAndBind(host, port, 
                    new BindParameter(
                        BindType.BIND_TX,
                        systemId,
                        password,
                        systemType,
                        TypeOfNumber.UNKNOWN,
                        NumberingPlanIndicator.UNKNOWN,
                        null
                    )
                );
                
                logger.info("[{}] Bound successfully", sessionKey);
                sessions.put(sessionKey, session);
                sessionToKeyMap.put(session.getSessionId(), sessionKey);
                sessionStates.put(sessionKey, SessionState.CONNECTED);
                backoff = Math.max(1000, smppProperties.getDefaultConfig().getReconnectDelay()); // Reset backoff
                
                // Create and schedule a dedicated SessionSender enforcing HP/NP token buckets
                SessionSender sender = new SessionSender(sessionKey, session, 
                    Math.max(1, sessionCfg.getTps()), hpMaxPercentage, 
                    outboundRepository, submitExecutor, meterRegistry);
                
                sessionSenders.put(sessionKey, sender);
                ScheduledFuture<?> future = senderScheduler.scheduleAtFixedRate(
                    sender,
                    0L, 1L, TimeUnit.SECONDS);
                sender.setScheduledFuture(future);
                senderFutures.put(sessionKey, future);
                
                // Monitor session state
                while (session != null && session.getSessionState().isBound()) {
                    try {
                        Thread.sleep(1000); // Check every second
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                logger.warn("[{}] Bind/connection error: {} [State: RETRYING]", sessionKey, e.getMessage());
                sessionStates.put(sessionKey, SessionState.RETRYING);
            } finally {
                // Cleanup
                if (session != null) {
                    try {
                        session.unbindAndClose();
                    } catch (Exception e) {
                        logger.warn("[" + sessionKey + "] Error during session cleanup: " + e.getMessage());
                    }
                    sessions.remove(sessionKey);
                    if (session.getSessionId() != null) {
                        sessionToKeyMap.remove(session.getSessionId());
                    }
                }
                
                // Cancel sender
                try {
                    ScheduledFuture<?> future = senderFutures.remove(sessionKey);
                    if (future != null) future.cancel(true);
                    SessionSender sender = sessionSenders.remove(sessionKey);
                    if (sender != null) sender.cancel();
                } catch (Exception e) {
                    logger.warn("[" + sessionKey + "] Error cleaning up sender: " + e.getMessage());
                }
            }
            
            // Only retry if shouldRetry is true
            if (!shouldRetry.getOrDefault(sessionKey, false)) {
                logger.info("[{}] Not retrying (manually stopped)", sessionKey);
                break;
            }
            
            // Exponential backoff before next bind attempt
            try {
                logger.info("[{}] Reconnect sleeping for {} ms", sessionKey, backoff);
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, maxBackoff);
        }
        
        sessionStates.put(sessionKey, SessionState.STOPPED);
        logger.info("[{}] Bind loop exiting [Final State: STOPPED]", sessionKey);
    }

    // Implement MessageReceiverListener interface methods
    @Override
    public void onAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
        // Note: For client sessions, we need to track which session this came from
        // We'll use a workaround by checking all active sessions
        String sessionKey = findSessionKeyForDeliverSm(deliverSm);
        
        if (sessionKey == null) {
            logger.warn("Received DeliverSm for unknown session");
            throw new ProcessRequestException("Unknown session", SMPPConstant.STAT_ESME_RINVSYSID);
        }

        try {
            byte[] shortMessage = deliverSm.getShortMessage();
            String text = shortMessage != null ? new String(shortMessage, StandardCharsets.UTF_8) : "";
            logger.info("[" + sessionKey + "] Received DeliverSm (short_message={}): {}", 
                shortMessage != null ? shortMessage.length : 0, text);

            String smscId = null;
            String rawStatus = null;

            // 1) Try to get message ID from receipted_message_id TLV (tag 0x001E)
            OptionalParameter receiptedMsgId = deliverSm.getOptionalParameter((short)0x001E);
            if (receiptedMsgId != null) {
                byte[] value = receiptedMsgId.serialize();
                // Skip the first 4 bytes (tag + length)
                if (value != null && value.length > 4) {
                    smscId = new String(value, 4, value.length - 4, StandardCharsets.UTF_8).trim();
                    logger.debug("[" + sessionKey + "] Extracted receipted_message_id TLV: {}", smscId);
                }
            }

            // 2) Try to get message state from message_state TLV (tag 0x0427)
            Integer numericState = null;
            OptionalParameter messageState = deliverSm.getOptionalParameter((short)0x0427);
            if (messageState != null) {
                byte[] value = messageState.serialize();
                // Skip the first 4 bytes (tag + length)
                if (value != null && value.length > 4) {
                    numericState = (int) value[4] & 0xFF;
                    logger.debug("[" + sessionKey + "] Extracted message_state TLV: {}", numericState);
                }
            }

            // 3) Fallback: try to parse short_message text for id: token
            if (smscId == null && text != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("id:([A-Za-z0-9-]+)").matcher(text);
                if (m.find()) smscId = m.group(1);
            }

            // 4) Another fallback: check optional TLV 'message_payload' (tag 0x0424)
            if (smscId == null) {
                OptionalParameter messagePayload = deliverSm.getOptionalParameter((short)0x0424);
                if (messagePayload != null) {
                    byte[] value = messagePayload.serialize();
                    // Skip the first 4 bytes (tag + length)
                    if (value != null && value.length > 4) {
                        String payload = new String(value, 4, value.length - 4, StandardCharsets.UTF_8);
                        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("id:([A-Za-z0-9-]+)").matcher(payload);
                        if (m2.find()) smscId = m2.group(1);
                    }
                }
            }

            // Determine raw status string for persistence
            if (text != null && !text.isBlank()) {
                rawStatus = text;
            } else if (numericState != null) {
                rawStatus = "STATE_" + numericState;
            } else {
                rawStatus = "DLR_UNKNOWN";
            }

            // Process the DLR if we have an ID
            if (smscId != null && !smscId.isBlank()) {
                processDeliveryReceipt(sessionKey, smscId, rawStatus);
            } else {
                logger.warn("[" + sessionKey + "] DeliverSm without parsable id: {}", text);
            }

        } catch (Exception e) {
            logger.error("[" + sessionKey + "] Error processing DeliverSm: " + e.getMessage(), e);
            throw new ProcessRequestException(e.getMessage(), SMPPConstant.STAT_ESME_RX_R_APPN);
        }
    }

    private void processDeliveryReceipt(String sessionKey, String smscId, String rawStatus) {
        SmsOutboundEntity outbound = outboundRepository.findBySmscMsgId(smscId);
        if (outbound != null) {
            String mappedStatus = mapDlrStatus(rawStatus);
            outbound.setStatus(mappedStatus);
            outboundRepository.save(outbound);

            com.cascade.smppmls.entity.SmsDlrEntity dlrEntity = new com.cascade.smppmls.entity.SmsDlrEntity();
            dlrEntity.setSmsOutboundId(outbound.getId());
            dlrEntity.setSmscMsgId(smscId);
            dlrEntity.setStatus(rawStatus);
            dlrEntity.setReceivedAt(java.time.Instant.now());
            dlrRepository.save(dlrEntity);
            
            logger.info("[" + sessionKey + "] Mapped DLR for outbound id={} smsc_msg_id={} mapped={}", 
                outbound.getId(), smscId, mappedStatus);
        } else {
            logger.warn("[" + sessionKey + "] Could not find outbound for smsc_msg_id={}", smscId);
        }
    }

    private String mapDlrStatus(String text) {
        if (text == null) return "DLR_UNKNOWN";
        String upperText = text.toUpperCase();
        if (upperText.contains("DELIVRD")) return "DELIVERED";
        if (upperText.contains("EXPIRED")) return "EXPIRED";
        if (upperText.contains("UNDELIV")) return "UNDELIVERABLE";
        return "DLR_" + (text.length() > 20 ? text.substring(0, 20) : text);
    }

    private String findSessionKeyForDeliverSm(DeliverSm deliverSm) {
        // Since MessageReceiverListener doesn't provide session context,
        // we'll need to use the first active session as a fallback
        // In production, you might want to use a more sophisticated approach
        if (!sessions.isEmpty()) {
            return sessions.keySet().iterator().next();
        }
        return null;
    }

    // Unused interface methods (required by MessageReceiverListener)
    @Override
    public DataSmResult onAcceptDataSm(DataSm dataSm, org.jsmpp.session.Session source) throws ProcessRequestException {
        throw new ProcessRequestException("DataSm not supported", SMPPConstant.STAT_ESME_RINVCMDID);
    }
    
    @Override
    public void onAcceptAlertNotification(AlertNotification alertNotification) {
        logger.debug("Alert notification received: " + alertNotification);
    }
    
    @Override
    public void stop() {
        logger.info("Shutting down SMPP session manager...");
        
        // Stop all SessionSender schedulers first
        sessionSenders.forEach((sessionKey, sender) -> {
            try {
                logger.info("[{}] Stopping SessionSender...", sessionKey);
                sender.cancel();
            } catch (Exception e) {
                logger.error("[{}] Error stopping SessionSender: {}", sessionKey, e.getMessage());
            }
        });
        
        // Shutdown the sender scheduler
        if (senderScheduler != null && !senderScheduler.isShutdown()) {
            logger.info("Shutting down sender scheduler...");
            senderScheduler.shutdown();
            try {
                if (!senderScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Sender scheduler did not terminate in time, forcing shutdown");
                    senderScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for sender scheduler shutdown");
                senderScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown the submit executor
        if (submitExecutor != null && !submitExecutor.isShutdown()) {
            logger.info("Shutting down submit executor...");
            submitExecutor.shutdown();
            try {
                if (!submitExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Submit executor did not terminate in time, forcing shutdown");
                    submitExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for submit executor shutdown");
                submitExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Unbind and close all SMPP sessions
        sessions.forEach((sessionKey, session) -> {
            if (session != null) {
                try {
                    if (session.getSessionState().isBound()) {
                        logger.info("[{}] Sending unbind request...", sessionKey);
                        session.unbindAndClose();
                        logger.info("[{}] Session unbound and closed", sessionKey);
                    } else {
                        logger.info("[{}] Session not bound, closing...", sessionKey);
                        session.close();
                    }
                } catch (Exception e) {
                    logger.error("[{}] Error during unbind/close: {}", sessionKey, e.getMessage());
                    try {
                        session.close();
                    } catch (Exception ex) {
                        logger.error("[{}] Error forcing close: {}", sessionKey, ex.getMessage());
                    }
                }
            }
        });
        
        sessions.clear();
        sessionSenders.clear();
        senderFutures.clear();
        
        logger.info("SMPP session manager shutdown complete");
    }
    
    @Override
    public void stopSession(String sessionId) {
        logger.info("Stopping session: {}", sessionId);
        
        sessionStates.put(sessionId, SessionState.STOPPING);
        shouldRetry.put(sessionId, false); // Prevent retries
        
        // Stop the sender
        ScheduledFuture<?> future = senderFutures.remove(sessionId);
        if (future != null) {
            future.cancel(false);
            logger.info("[{}] Sender task cancelled", sessionId);
        }
        
        // Remove sender
        SessionSender sender = sessionSenders.remove(sessionId);
        if (sender != null) {
            sender.cancel();
        }
        
        // Unbind and close session
        SMPPSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                if (session.getSessionState().isBound()) {
                    session.unbindAndClose();
                    logger.info("[{}] Session unbound and closed", sessionId);
                } else {
                    session.close();
                    logger.info("[{}] Session closed", sessionId);
                }
            } catch (Exception e) {
                logger.error("[{}] Error stopping session: {}", sessionId, e.getMessage());
            }
        }
        
        sessionStates.put(sessionId, SessionState.STOPPED);
        logger.info("[{}] Session stopped successfully", sessionId);
    }
    
    @Override
    public void startSession(String sessionId) {
        logger.info("Starting session: {}", sessionId);
        
        // Parse sessionId to get operator and systemId
        String[] parts = sessionId.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid sessionId format. Expected 'operator:systemId'");
        }
        
        String operatorId = parts[0];
        String systemId = parts[1];
        
        // Find the session configuration
        SmppProperties.Operator operator = smppProperties.getOperators().get(operatorId);
        if (operator == null) {
            throw new IllegalArgumentException("Operator not found: " + operatorId);
        }
        
        SmppProperties.Session sessionCfg = operator.getSessions().stream()
            .filter(s -> s.getSystemId().equals(systemId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + systemId));
        
        // Enable retries and set state
        sessionStates.put(sessionId, SessionState.STARTING);
        shouldRetry.put(sessionId, true);
        
        // Start the bind loop for this session
        Executors.newSingleThreadExecutor().execute(() -> 
            bindLoop(sessionId, operatorId, sessionCfg, operator.getHost(), operator.getPort())
        );
        
        logger.info("[{}] Session start initiated", sessionId);
    }
    
    /**
     * Get the current state of a session
     */
    public SessionState getSessionState(String sessionId) {
        return sessionStates.getOrDefault(sessionId, SessionState.STOPPED);
    }
    
    /**
     * Get all session states
     */
    public Map<String, SessionState> getAllSessionStates() {
        return new HashMap<>(sessionStates);
    }
}
