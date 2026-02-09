package com.cascade.smppmls.service;

import com.cascade.smppmls.entity.DelayedMessageLog;
import com.cascade.smppmls.repository.DelayedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyArchiverService {

    private final DelayedMessageRepository repository;

    @Value("${app.archiving.path:./archives}")
    private String archivePath;

    // Run at midnight every day
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void archiveDailyLogs() {
        log.info("Starting daily archive of delayed messages...");
        
        long count = repository.count();
        if (count == 0) {
            log.info("No delayed messages to archive.");
            return;
        }
        log.info("Found {} messages to archive", count);

        String dateStr = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE); // Archive for previous day
        String filename = "delayed_messages_" + dateStr + ".csv";
        
        try {
            Path archiveDir = Paths.get(archivePath);
            if (!Files.exists(archiveDir)) {
                Files.createDirectories(archiveDir);
            }

            File file = archiveDir.resolve(filename).toFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                // Write Header
                writer.write("ID,OriginalMessageID,MSISDN,EntryTime,ExitTime,DurationSeconds,Status,Reason");
                writer.newLine();

                // Process in batches
                int pageSize = 1000;
                int page = 0;
                org.springframework.data.domain.Page<DelayedMessageLog> logsPage;
                
                do {
                    logsPage = repository.findAll(org.springframework.data.domain.PageRequest.of(page, pageSize));
                    
                    for (DelayedMessageLog logEntry : logsPage.getContent()) {
                        writer.write(String.format("%d,%d,%s,%s,%s,%d,%s,%s",
                                logEntry.getId(),
                                logEntry.getOriginalMessageId(),
                                logEntry.getMsisdn(),
                                logEntry.getEntryTime(),
                                logEntry.getExitTime(),
                                logEntry.getDurationSeconds(),
                                logEntry.getStatus(),
                                escapeCsv(logEntry.getReason())
                        ));
                        writer.newLine();
                    }
                    
                    // Flush periodically
                    writer.flush();
                    
                    page++;
                    log.debug("Archived page {}/{}", page, logsPage.getTotalPages());
                } while (logsPage.hasNext());
            }

            log.info("Archived delayed messages to {}", file.getAbsolutePath());

            // Cleanup DB in batches to avoid long locks/transaction timeouts
            log.info("Starting cleanup of archived records...");
            int deletedCount = 0;
            org.springframework.data.domain.Page<DelayedMessageLog> deletePage;
            
            do {
                // Always fetch page 0 as we are deleting
                deletePage = repository.findAll(org.springframework.data.domain.PageRequest.of(0, 1000));
                if (deletePage.hasContent()) {
                    repository.deleteAll(deletePage.getContent());
                    deletedCount += deletePage.getNumberOfElements();
                    log.debug("Deleted {} records so far", deletedCount);
                }
            } while (deletePage.hasNext());
            
            log.info("Deleted {} archived records from database.", deletedCount);

        } catch (IOException e) {
            log.error("Failed to archive delayed messages: {}", e.getMessage(), e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
