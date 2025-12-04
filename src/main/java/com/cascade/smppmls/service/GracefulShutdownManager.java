package com.cascade.smppmls.service;

import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.smpp.JsmppSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.stereotype.Service;

import java.io.Console;
import java.util.Scanner;

/**
 * Manages graceful shutdown sequence for the SMPP application
 */
@Slf4j
@Service
public class GracefulShutdownManager {

    private final SmsOutboundRepository outboundRepository;
    private final JsmppSessionManager sessionManager;
    private final WebServer webServer;

    public GracefulShutdownManager(
            SmsOutboundRepository outboundRepository,
            JsmppSessionManager sessionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false) WebServer webServer) {
        this.outboundRepository = outboundRepository;
        this.sessionManager = sessionManager;
        this.webServer = webServer;
    }

    @Value("${shutdown.max-wait-seconds:300}")
    private int maxWaitSeconds;

    @Value("${shutdown.interactive:true}")
    private boolean interactive;

    /**
     * Print shutdown banner
     */
    public void printShutdownBanner() {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║           GRACEFUL SHUTDOWN INITIATED                  ║");
        log.info("╚════════════════════════════════════════════════════════╝");
    }

    /**
     * Stop accepting new HTTP requests
     */
    public void stopAcceptingRequests() {
        try {
            if (webServer == null) {
                log.warn("WebServer not available, skipping request pause");
                return;
            }
            if (webServer instanceof TomcatWebServer tomcatWebServer) {
                tomcatWebServer.getTomcat().getConnector().pause();
                log.info("✓ Server stopped accepting new requests");
            } else {
                log.warn("Non-Tomcat server, cannot pause connector");
            }
        } catch (Exception e) {
            log.warn("Could not pause server connector: {}", e.getMessage());
        }
    }

    /**
     * Count pending messages in the queue
     */
    public MessageStats countPendingMessages() {
        long queued = outboundRepository.countByStatus("QUEUED");
        long sent = outboundRepository.countByStatus("SENT");
        long inFlight = sent; // Messages sent but no DLR yet
        
        return new MessageStats(queued, inFlight, queued + inFlight);
    }

    /**
     * Display message statistics in console
     */
    public void displayMessageStats(MessageStats stats) {
        log.info("║                                                        ║");
        log.info("║  Pending Messages:                                     ║");
        log.info("║    • Queued:    {} messages{}", 
            String.format("%-4d", stats.queued()), " ".repeat(Math.max(0, 30 - String.valueOf(stats.queued()).length())));
        log.info("║    • In-flight: {} messages{}", 
            String.format("%-4d", stats.inFlight()), " ".repeat(Math.max(0, 30 - String.valueOf(stats.inFlight()).length())));
        log.info("║    • Total:     {} messages{}", 
            String.format("%-4d", stats.total()), " ".repeat(Math.max(0, 30 - String.valueOf(stats.total()).length())));
        log.info("║                                                        ║");
    }

    /**
     * Prompt user for shutdown decision
     * @return true if user wants to wait, false for immediate shutdown
     */
    public boolean promptUserDecision(int totalPending) {
        if (totalPending == 0) {
            log.info("✓ No pending messages, proceeding with immediate shutdown");
            return false;
        }

        if (!interactive) {
            log.info("⚠ Non-interactive mode, defaulting to wait for messages (max {} seconds)", maxWaitSeconds);
            return true;
        }

        log.info("║  Options:                                              ║");
        log.info("║    1. Wait for messages to complete (recommended)      ║");
        log.info("║    2. Shutdown immediately (messages may be lost)      ║");
        log.info("╚════════════════════════════════════════════════════════╝");
        log.info("");

        Console console = System.console();
        String choice;
        
        if (console != null) {
            choice = console.readLine("Enter choice (1 or 2): ");
        } else {
            // Fallback to Scanner for non-console environments
            log.warn("Console not available, using Scanner for input");
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter choice (1 or 2): ");
            choice = scanner.nextLine();
        }

        boolean shouldWait = "1".equals(choice != null ? choice.trim() : "");
        
        if (shouldWait) {
            log.info("✓ User chose to wait for message completion");
        } else {
            log.info("⚠ User chose immediate shutdown");
        }
        
        return shouldWait;
    }

    /**
     * Wait for message completion with progress monitoring
     */
    public void waitForMessageCompletion(int maxWaitSeconds) {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║  Waiting for messages to complete...                   ║");
        log.info("║  Maximum wait time: {} seconds{}", 
            String.format("%-4d", maxWaitSeconds), " ".repeat(Math.max(0, 28 - String.valueOf(maxWaitSeconds).length())));
        log.info("╚════════════════════════════════════════════════════════╝");

        long startTime = System.currentTimeMillis();
        long maxWaitMs = maxWaitSeconds * 1000L;
        int checkCount = 0;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            MessageStats stats = countPendingMessages();

            if (stats.total() == 0) {
                log.info("✓ All messages processed successfully");
                return;
            }

            checkCount++;
            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
            
            if (checkCount % 2 == 0) { // Log every 10 seconds (5s * 2)
                log.info("⏳ Waiting... Queued: {}, In-flight: {}, Total: {} | Elapsed: {}s / {}s",
                    stats.queued(), stats.inFlight(), stats.total(), elapsedSeconds, maxWaitSeconds);
            }

            try {
                Thread.sleep(5000); // Check every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Wait interrupted");
                break;
            }
        }

        MessageStats remaining = countPendingMessages();
        if (remaining.total() > 0) {
            log.warn("⚠ Timeout reached. {} messages still pending (Queued: {}, In-flight: {})",
                remaining.total(), remaining.queued(), remaining.inFlight());
        }
    }

    /**
     * Print shutdown complete banner
     */
    public void printShutdownComplete() {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║           GRACEFUL SHUTDOWN COMPLETE                   ║");
        log.info("╚════════════════════════════════════════════════════════╝");
    }
}
