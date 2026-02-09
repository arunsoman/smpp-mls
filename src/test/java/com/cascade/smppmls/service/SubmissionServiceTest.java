package com.cascade.smppmls.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.model.QueuedMessage;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.util.IdGenerator;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock
    private RedisQueueService redisQueueService;
    
    @Mock
    private OperatorRouter router;
    
    @Mock
    private IdGenerator idGenerator;
    
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        service = new SubmissionService(redisQueueService, router, idGenerator);
    }

    @Test
    void testSubmit_NewMessage_QueuesToRedis() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Message");
        req.setPriority("NORMAL");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        when(idGenerator.nextId()).thenReturn(100L);
        
        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertNotNull(resp);
        assertEquals("100", resp.getMessageId());
        assertEquals("QUEUED", resp.getStatus());
        
        // Verify pushed to queue
        verify(redisQueueService, times(1)).pushToQueue(eq("awcc:client"), any(QueuedMessage.class));
        verify(redisQueueService, times(1)).addToPendingInsert(any(QueuedMessage.class));
    }

    @Test
    void testSubmit_IdempotencyHit_ReturnsCachedResponse() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Duplicate");
        req.setClientMsgId("unique-id-123");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // Mock cache hit
        when(redisQueueService.getCachedMessageId("unique-id-123")).thenReturn(200L);

        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertEquals("200", resp.getMessageId());
        assertEquals("QUEUED", resp.getStatus());
        
        // Should NOT push to queue again
        verify(redisQueueService, never()).pushToQueue(anyString(), any());
        verify(redisQueueService, never()).addToPendingInsert(any());
    }

    @Test
    void testSubmit_NewWithClientMsgId_CachesId() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Message");
        req.setClientMsgId("unique-id-new");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        when(idGenerator.nextId()).thenReturn(300L);
        
        // Mock cache miss
        when(redisQueueService.getCachedMessageId("unique-id-new")).thenReturn(null);

        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertEquals("300", resp.getMessageId());
        
        // Verify pushed to queue AND cached
        verify(redisQueueService).pushToQueue(eq("awcc:client"), any(QueuedMessage.class));
        verify(redisQueueService).cacheIdempotency(eq("unique-id-new"), eq(300L), anyInt());
    }
}
