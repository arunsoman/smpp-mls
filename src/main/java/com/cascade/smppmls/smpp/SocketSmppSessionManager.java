package com.cascade.smppmls.smpp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocketSmppSessionManager implements SmppSessionManager {

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
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
            log.warn("No SMPP operators configured. Session manager will not start any binds.");
            return;
        }

        smppProperties.getOperators().forEach((operatorId, operator) -> {
            if (operator.getSessions() == null || operator.getSessions().isEmpty()) {
                log.warn("Operator {} has no sessions configured", operatorId);
                return;
            }

            // Check if multiple sessions require uuId
            if (operator.getSessions().size() > 1) {
                for (SmppProperties.Session s : operator.getSessions()) {
                    if (s.getUuId() == null || s.getUuId().trim().isEmpty()) {
                        throw new IllegalStateException(
                            String.format("Operator '%s' has multiple sessions but session with systemId '%s' is missing uuId. " +
                                "uuId is required when multiple sessions are configured.", 
                                operatorId, s.getSystemId()));
                    }
                }
            }

            for (SmppProperties.Session s : operator.getSessions()) {
                // Use uuId if available, otherwise use operatorId:systemId for single session
                final String sessionKey;
                if (s.getUuId() != null && !s.getUuId().trim().isEmpty()) {
                    sessionKey = s.getUuId();
                } else {
                    sessionKey = operatorId + ":" + s.getSystemId();
                }
                
                sessionHealth.put(sessionKey, false);
                sessionStates.put(sessionKey, SessionState.STARTING);
                shouldRetry.put(sessionKey, true); // Auto-start sessions should retry
                
                // schedule a connect attempt with initial delay 0 using virtual thread
                Thread thread = Thread.ofVirtual()
                    .name("SMPP-" + sessionKey)
                    .start(() -> connectLoop(operatorId, operator.getHost(), operator.getPort(), s, sessionKey));
                sessionThreads.put(sessionKey, thread);
            }
        });
    }

    private void connectLoop(String operatorId, String host, int port, SmppProperties.Session session, String sessionKey) {
        int backoff = Math.max(1000, smppProperties.getDefaultConfig().getReconnectDelay());
        
        // Build a descriptive session identifier for logging
        String sessionDesc = session.getUuId() != null && !session.getUuId().trim().isEmpty() 
            ? String.format("%s (systemId=%s, operator=%s)", session.getUuId(), session.getSystemId(), operatorId)
            : String.format("%s (uuid:)", sessionKey);
        
        while (!Thread.currentThread().isInterrupted() && shouldRetry.getOrDefault(sessionKey, false)) {
            try (Socket socket = new Socket()) {
                log.info("[{}] Attempting TCP connect to {}:{} [State: {}]", 
                    sessionDesc, host, port, sessionStates.get(sessionKey));
                
                socket.connect(new InetSocketAddress(host, port), 5_000);
                
                // Connection successful
                log.info("[{}] TCP connect successful - marking session healthy", sessionDesc);
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
                        log.warn("[{}] Socket no longer connected; will reconnect", sessionDesc);
                        sessionHealth.put(sessionKey, false);
                        sessionStates.put(sessionKey, SessionState.RETRYING);
                        break;
                    }
                    log.debug("[{}] session healthy (keepalive)", sessionDesc);
                }

            } catch (IOException e) {
                sessionHealth.put(sessionKey, false);
                sessionStates.put(sessionKey, SessionState.RETRYING);
                
                // Only retry if shouldRetry is true
                if (!shouldRetry.getOrDefault(sessionKey, false)) {
                    log.info("[{}] Not retrying connection (manually stopped)", sessionDesc);
                    break;
                }
                
                log.warn("[{}] Connect failed to {}:{} - will retry in {} ms. Reason: {}", 
                    sessionDesc, host, port, backoff, e.getMessage());
                
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
        log.info("[{}] Connect loop ending [Final State: STOPPED]", sessionDesc);
    }

    @Override
    public void stop() {
        try {
            log.info("Shutting down SMPP session manager");
            
            // Shutdown virtual thread executor
            if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
                virtualThreadExecutor.shutdown();
                if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Virtual thread executor did not terminate promptly");
                    virtualThreadExecutor.shutdownNow();
                }
            }
            
            // Shutdown scheduler
            scheduler.shutdownNow();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate promptly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public void stopSession(String sessionId) {
        log.info("Stopping session: {}", sessionId);
        
        sessionStates.put(sessionId, SessionState.STOPPING);
        shouldRetry.put(sessionId, false); // Prevent retries
        
        Thread thread = sessionThreads.get(sessionId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            log.info("[{}] Session thread interrupted", sessionId);
            
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
        log.info("[{}] Session stopped successfully", sessionId);
    }
    
    @Override
    public void startSession(String sessionId) {
        log.info("Starting session: {}", sessionId);
        
        // Check if already running
        Thread existingThread = sessionThreads.get(sessionId);
        if (existingThread != null && existingThread.isAlive()) {
            log.warn("[{}] Session already running, ignoring start request", sessionId);
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
        
        // Start a new virtual thread for this session
        sessionHealth.put(sessionId, false);
        sessionStates.put(sessionId, SessionState.STARTING);
        shouldRetry.put(sessionId, true); // Enable retries
        
        // Make variables effectively final for lambda
        final String finalOperatorId = operatorId;
        final String finalHost = operator.getHost();
        final int finalPort = operator.getPort();
        final SmppProperties.Session finalSessionCfg = sessionCfg;
        
        Thread thread = Thread.ofVirtual()
            .name("SMPP-" + sessionId)
            .start(() -> connectLoop(finalOperatorId, finalHost, finalPort, finalSessionCfg, sessionId));
        sessionThreads.put(sessionId, thread);
        
        log.info("[{}] Session started", sessionId);
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
