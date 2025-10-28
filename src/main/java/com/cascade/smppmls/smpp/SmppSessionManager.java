package com.cascade.smppmls.smpp;

import java.util.Map;

public interface SmppSessionManager {

    /**
     * Returns a map of session identifiers to health status (true = bound/healthy)
     */
    Map<String, Boolean> getSessionHealth();

    /**
     * Start all configured sessions
     */
    void start();

    /**
     * Stop all sessions and release resources
     */
    void stop();
    
    /**
     * Stop a specific session by ID
     */
    void stopSession(String sessionId);
    
    /**
     * Start a specific session by ID
     */
    void startSession(String sessionId);
}
