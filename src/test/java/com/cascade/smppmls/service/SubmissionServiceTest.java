package com.cascade.smppmls.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock
    private SmsOutboundRepository outboundRepository;
    
    @Mock
    private OperatorRouter router;
    
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        service = new SubmissionService(outboundRepository, router);
    }

    @Test
    void testSubmit_NewMessage_CreatesEntity() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Message");
        req.setPriority("NORMAL");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        when(outboundRepository.findBySignature(anyString())).thenReturn(Optional.empty());
        
        SmsOutboundEntity savedEntity = new SmsOutboundEntity();
        savedEntity.setId(100L);
        savedEntity.setStatus("QUEUED");
        when(outboundRepository.save(any(SmsOutboundEntity.class))).thenAnswer(invocation -> {
            SmsOutboundEntity e = invocation.getArgument(0);
            e.setId(100L);
            return e;
        });

        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertNotNull(resp);
        assertEquals("100", resp.getMessageId());
        verify(outboundRepository, times(1)).save(any(SmsOutboundEntity.class));
    }

    @Test
    void testSubmit_DuplicateContent_ReturnsExisting() {
        // Arrange
        SubmitRequest req1 = new SubmitRequest();
        req1.setMsisdn("0701234567");
        req1.setMessage("Test Duplicate");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // Mock existing entity
        SmsOutboundEntity existing = new SmsOutboundEntity();
        existing.setId(200L);
        existing.setMsisdn("93701234567");
        existing.setMessage("Test Duplicate");
        existing.setStatus("SENT");
        existing.setSignature("dummy-signature");
        existing.setRequestId("req-200");
        
        when(outboundRepository.findBySignature(anyString())).thenReturn(Optional.of(existing));

        // Act
        SubmitResponse resp = service.submit(req1);

        // Assert
        assertEquals("200", resp.getMessageId());
        assertEquals("SENT", resp.getStatus());
        // Should NOT save a new entity
        verify(outboundRepository, never()).save(any(SmsOutboundEntity.class));
    }
    @Test
    void testSubmit_DuplicateClientMsgId_ReturnsFirst() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Duplicate ClientMsgId");
        req.setClientMsgId("unique-id-123");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // Mock existing entities (duplicates)
        SmsOutboundEntity existing1 = new SmsOutboundEntity();
        existing1.setId(301L);
        existing1.setClientMsgId("unique-id-123");
        existing1.setStatus("SENT");
        existing1.setRequestId("req-301");
        
        SmsOutboundEntity existing2 = new SmsOutboundEntity();
        existing2.setId(302L);
        existing2.setClientMsgId("unique-id-123");
        existing2.setStatus("QUEUED");
        existing2.setRequestId("req-302");
        
        // Return list containing duplicates
        when(outboundRepository.findByClientMsgId("unique-id-123")).thenReturn(java.util.List.of(existing1, existing2));

        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertNotNull(resp);
        // Should return the first one found
        assertEquals("301", resp.getMessageId());
        assertEquals("SENT", resp.getStatus());
        verify(outboundRepository, never()).save(any(SmsOutboundEntity.class));
    }
}
