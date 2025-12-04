package com.cascade.smppmls.service;

import com.cascade.smppmls.smpp.JsmppSessionManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Orchestrates the graceful shutdown sequence for the SMPP application
 */
@Slf4j
@Service
public class ApplicationShutdownService {

    private final H2DatabaseDumpService h2DatabaseDumpService;
    private final GracefulShutdownManager shutdownManager;
    private final JsmppSessionManager sessionManager;
    
    private volatile boolean shutdownInProgress = false;

    public ApplicationShutdownService(
            H2DatabaseDumpService h2DatabaseDumpService,
            GracefulShutdownManager shutdownManager,
            JsmppSessionManager sessionManager) {
        this.h2DatabaseDumpService = h2DatabaseDumpService;
        this.shutdownManager = shutdownManager;
        this.sessionManager = sessionManager;
        
        // Register JVM shutdown hook as backup (in case @PreDestroy doesn't run)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdownInProgress) {
                log.warn("JVM shutdown hook triggered - @PreDestroy may not have run!");
                log.warn("Attempting graceful shutdown via shutdown hook...");
                performShutdown();
            }
        }, "graceful-shutdown-hook"));
        
        log.info("ApplicationShutdownService initialized with shutdown hook registered");
    }

    @PreDestroy
    public void onShutdown() {
        log.info("=".repeat(60));
        log.info("@PreDestroy method called - Starting graceful shutdown sequence");
        log.info("=".repeat(60));
        performShutdown();
    }

    /**
     * Performs the actual shutdown sequence
     * Called by both @PreDestroy and JVM shutdown hook
     */
    private void performShutdown() {
        if (shutdownInProgress) {
            log.warn("Shutdown already in progress, skipping duplicate call");
            return;
        }
        
        shutdownInProgress = true;
        
        try {
            // Phase 1: Print banner and stop accepting requests
            shutdownManager.printShutdownBanner();
            shutdownManager.stopAcceptingRequests();

            // Phase 2: Check pending messages
            MessageStats stats = shutdownManager.countPendingMessages();
            shutdownManager.displayMessageStats(stats);

            // Phase 3: User decision
            boolean shouldWait = shutdownManager.promptUserDecision((int) stats.total());

            // Phase 4: Wait for messages (if chosen)
            if (shouldWait) {
                shutdownManager.waitForMessageCompletion(300); // 5 minutes max
            } else {
                log.info("⚠ Skipping message wait, proceeding with immediate shutdown");
            }

            // Phase 5: SMPP cleanup (this will call JsmppSessionManager.stop())
            log.info("╔════════════════════════════════════════════════════════╗");
            log.info("║  Shutting down SMPP sessions...                       ║");
            log.info("╚════════════════════════════════════════════════════════╝");
            sessionManager.stop();

            // Phase 6: Persist database
            persistDatabase();

            // Final banner
            shutdownManager.printShutdownComplete();

        } catch (Exception e) {
            log.error("Error during graceful shutdown: {}", e.getMessage(), e);
        }
    }

    /**
     * Persist H2 database to disk with timestamp
     */
    private void persistDatabase() {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║  Persisting H2 database to disk...                    ║");
        log.info("╚════════════════════════════════════════════════════════╝");

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String dumpFile = "./dump/smpp_db_" + timestamp + ".sql";

            h2DatabaseDumpService.dumpDatabaseToFile(dumpFile);

            log.info("✓ Database persisted successfully: {}", dumpFile);
        } catch (SQLException | IOException e) {
            log.error("✗ Failed to persist database: {}", e.getMessage());
            e.printStackTrace();
        }
    }
}

