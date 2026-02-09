package com.cascade.smppmls.service;

import com.cascade.smppmls.entity.DelayedMessageLog;
import com.cascade.smppmls.repository.DelayedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyArchiverServiceTest {

    @Mock
    private DelayedMessageRepository repository;

    private DailyArchiverService archiverService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        archiverService = new DailyArchiverService(repository);
        ReflectionTestUtils.setField(archiverService, "archivePath", tempDir.toString());
    }

    @Test
    void testArchiveDailyLogs_WithPagination() {
        // Arrange
        DelayedMessageLog log1 = new DelayedMessageLog();
        log1.setId(1L);
        log1.setMsisdn("1234567890");

        DelayedMessageLog log2 = new DelayedMessageLog();
        log2.setId(2L);
        log2.setMsisdn("0987654321");

        // Mock count
        when(repository.count()).thenReturn(2000L);

        // Mock pagination for reading
        Page<DelayedMessageLog> page0 = new PageImpl<>(Collections.nCopies(1000, log1), PageRequest.of(0, 1000), 2000);
        Page<DelayedMessageLog> page1 = new PageImpl<>(Collections.nCopies(1000, log2), PageRequest.of(1, 1000), 2000);
        Page<DelayedMessageLog> emptyPage = Page.empty();

        // Sequence for findAll(PageRequest.of(0, 1000)):
        // 1. Read Page 0
        // 2. Delete Batch 1
        // 3. Delete Batch 2 (should return empty or hasNext=false to stop delete loop)
        when(repository.findAll(eq(PageRequest.of(0, 1000))))
            .thenReturn(page0) // Read Page 0
            .thenReturn(page0) // Delete Batch 0 (hasNext=true)
            .thenReturn(emptyPage); // Delete Batch 1 (hasNext=false, terminate)

        // Sequence for findAll(PageRequest.of(1, 1000)):
        // 1. Read Page 1
        when(repository.findAll(eq(PageRequest.of(1, 1000))))
            .thenReturn(page1); // Read Page 1

        // Act
        archiverService.archiveDailyLogs();

        // Assert
        verify(repository, times(3)).findAll(eq(PageRequest.of(0, 1000)));
        verify(repository, times(1)).findAll(eq(PageRequest.of(1, 1000)));
        verify(repository, atLeastOnce()).deleteAll(any());
    }
    
    @Test
    void testArchiveDailyLogs_SimpleFlow() {
        // Simpler test with Mockito chaining
        when(repository.count()).thenReturn(100L);
        
        DelayedMessageLog logEntry = new DelayedMessageLog();
        logEntry.setId(1L);
        logEntry.setMsisdn("123");
        
        Page<DelayedMessageLog> page = new PageImpl<>(List.of(logEntry));
        Page<DelayedMessageLog> emptyPage = Page.empty();
        
        // Reading loop calls - combined with delete loop calls for findAll(0)
        // when(repository.findAll(PageRequest.of(0, 1000))).thenReturn(page); // Overwritten below
        // when(repository.findAll(PageRequest.of(1, 1000))).thenReturn(emptyPage); // Unnecessary as page hasNext=false
        
        // Correct mocking:
        // First call to findAll(0, 1000) returns data (Reading)
        // Second call to findAll(0, 1000) returns data (Deleting batch 1)
        // Third call to findAll(0, 1000) returns empty (Deleting batch 2 - done)
        
        when(repository.findAll(PageRequest.of(0, 1000)))
            .thenReturn(page) // Read
            .thenReturn(page) // Delete 1
            .thenReturn(emptyPage); // Delete done
            
        archiverService.archiveDailyLogs();
        
        // Verify deletion
        verify(repository, times(1)).deleteAll(anyList());
        
        // Verify file created
        File[] files = tempDir.toFile().listFiles((d, name) -> name.startsWith("delayed_messages_") && name.endsWith(".csv"));
        assertTrue(files != null && files.length > 0);
    }
}
