package com.cascade.smppmls.smpp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;

@Component
public class SocketSmppSessionManager implements SmppSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SocketSmppSessionManager.class);

    private final SmppProperties smppProperties;

    // Session states
    public enum SessionState {
        STOPPED,      // Manually stopped, won't retry
        STARTING,     // Initial connection attempt
        CONNECTED,    // Successfully connected
        RETRYING,     // Connection failed, will retry
        STOPPING      // Being stopped
    }
    
    // sessionKey -> state
    private final Map<String, SessionState> sessionStates = Collections.synchronizedMap(new HashMap<>());
    
    // sessionKey -> healthy (for backward compatibility)
    private final Map<String, Boolean> sessionHealth = Collections.synchronizedMap(new HashMap<>());
    
    // Track running threads for each session
    private final Map<String, Thread> sessionThreads = Collections.synchronizedMap(new HashMap<>());
    
    // Track if session should keep retrying
    private final Map<String, Boolean> shouldRetry = Collections.synchronizedMap(new HashMap<>());

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public SocketSmppSessionManager(SmppProperties smppProperties) {
        this.smppProperties = smppProperties;
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
        Map<String, Boolean> health = new HashMap<>();
        
        // Include all sessions that have a state (even if stopped)
        sessionStates.keySet().forEach(sessionKey -> {
            health.put(sessionKey, sessionHealth.getOrDefault(sessionKey, false));
        });
        
        return health;
    }

    @Override
    public void start() {
        if (smppProperties.getOperators() == null || smppProperties.getOperators().isEmpty()) {
            logger.warn("No SMPP operators configured. Session manager will not start any binds.");
            return;
        }

        smppProperties.getOperators().forEach((operatorId, operator) -> {
            if (operator.getSessions() == null || operator.getSessions().isEmpty()) {
                logger.warn("Operator {} has no sessions configured", operatorId);
                return;
            }

            int idx = 0;
            for (SmppProperties.Session s : operator.getSessions()) {
                final String sessionKey = operatorId + ":" + (s.getSystemId() != null ? s.getSystemId() + "-"+ ++idx : "session-" + ++idx);
                sessionHealth.put(sessionKey, false);
                sessionStates.put(sessionKey, SessionState.STARTING);
                shouldRetry.put(sessionKey, true); // Auto-start sessions should retry
                
                // schedule a connect attempt with initial delay 0
                Thread thread = new Thread(() -> connectLoop(operatorId, operator.getHost(), operator.getPort(), s, sessionKey));
                thread.setName("SMPP-" + sessionKey);
                sessionThreads.put(sessionKey, thread);
                thread.start();
            }
        });
    }

    private void connectLoop(String operatorId, String host, int port, SmppProperties.Session session, String sessionKey) {
        int backoff = Math.max(1000, smppProperties.getDefaultConfig().getReconnectDelay());
        
        while (!Thread.currentThread().isInterrupted() && shouldRetry.getOrDefault(sessionKey, false)) {
            try (Socket socket = new Socket()) {
                logger.info("[{}] Attempting TCP connect to {}:{} (systemId={}) [State: {}]", 
                    sessionKey, host, port, session.getSystemId(), sessionStates.get(sessionKey));
                
                socket.connect(new InetSocketAddress(host, port), 5_000);
                
                // Connection successful
                logger.info("[{}] TCP connect successful - marking session healthy", sessionKey);
                sessionHealth.put(sessionKey, true);
                sessionStates.put(sessionKey, SessionState.CONNECTED);
                backoff = Math.max(1000, smppProperties.getDefaultConfig().getReconnectDelay()); // Reset backoff

                // Keep socket open and periodically send an enquire-like health check (TCP-level: just sleep)
                while (!Thread.currentThread().isInterrupted() && shouldRetry.getOrDefault(sessionKey, false)) {
                    try {
                        Thread.sleep(Math.min(30_000, smppProperties.getDefaultConfig().getEnquireLinkInterval()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    // simple TCP connectivity check
                    if (socket.isClosed() || !socket.isConnected()) {
                        logger.warn("[{}] Socket no longer connected; will reconnect", sessionKey);
                        sessionHealth.put(sessionKey, false);
                        sessionStates.put(sessionKey, SessionState.RETRYING);
                        break;
                    }
                    logger.debug("[{}] session healthy (keepalive)", sessionKey);
                }

            } catch (IOException e) {
                sessionHealth.put(sessionKey, false);
                sessionStates.put(sessionKey, SessionState.RETRYING);
                
                // Only retry if shouldRetry is true
                if (!shouldRetry.getOrDefault(sessionKey, false)) {
                    logger.info("[{}] Not retrying connection (manually stopped)", sessionKey);
                    break;
                }
                
                logger.warn("[{}] Connect failed to {}:{} - will retry in {} ms. Reason: {}", 
                    sessionKey, host, port, backoff, e.getMessage());
                
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, 60_000); // exponential backoff cap
            }
        }

        // Clean exit
        sessionHealth.put(sessionKey, false);
        sessionStates.put(sessionKey, SessionState.STOPPED);
        logger.info("[{}] Connect loop ending [Final State: STOPPED]", sessionKey);
    }

    @Override
    public void stop() {
        try {
            logger.info("Shutting down SMPP session manager scheduler");
            scheduler.shutdownNow();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate promptly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public void stopSession(String sessionId) {
        logger.info("Stopping session: {}", sessionId);
        
        sessionStates.put(sessionId, SessionState.STOPPING);
        shouldRetry.put(sessionId, false); // Prevent retries
        
        Thread thread = sessionThreads.get(sessionId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            logger.info("[{}] Session thread interrupted", sessionId);
            
            // Wait a bit for graceful shutdown
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        sessionThreads.remove(sessionId);
        sessionHealth.put(sessionId, false);
        sessionStates.put(sessionId, SessionState.STOPPED);
        logger.info("[{}] Session stopped successfully", sessionId);
    }
    
    @Override
    public void startSession(String sessionId) {
        logger.info("Starting session: {}", sessionId);
        
        // Check if already running
        Thread existingThread = sessionThreads.get(sessionId);
        if (existingThread != null && existingThread.isAlive()) {
            logger.warn("[{}] Session already running, ignoring start request", sessionId);
            return;
        }
        
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
        
        // Start a new thread for this session
        sessionHealth.put(sessionId, false);
        sessionStates.put(sessionId, SessionState.STARTING);
        shouldRetry.put(sessionId, true); // Enable retries
        
        Thread thread = new Thread(() -> connectLoop(operatorId, operator.getHost(), operator.getPort(), sessionCfg, sessionId));
        thread.setName("SMPP-" + sessionId);
        sessionThreads.put(sessionId, thread);
        thread.start();
        
        logger.info("[{}] Session started", sessionId);
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
