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
    void setUp() throws Exception {
        service = new SubmissionService(outboundRepository, router);
        // Set dedupWindowMinutes since @Value isn't processed in unit tests
        java.lang.reflect.Field windowField = SubmissionService.class.getDeclaredField("dedupWindowMinutes");
        windowField.setAccessible(true);
        windowField.setInt(service, 5);
    }


    @Test
    void testSubmit_NewMessage_CreatesEntity() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Message");
        req.setPriority("NORMAL");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
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
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setMessage("Test Duplicate");
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // First call: save succeeds and populates in-memory dedup cache
        SmsOutboundEntity savedEntity = new SmsOutboundEntity();
        savedEntity.setId(200L);
        savedEntity.setMsisdn("93701234567");
        savedEntity.setMessage("Test Duplicate");
        savedEntity.setStatus("QUEUED");
        savedEntity.setRequestId("req-200");
        
        when(outboundRepository.save(any(SmsOutboundEntity.class))).thenAnswer(invocation -> {
            SmsOutboundEntity e = invocation.getArgument(0);
            e.setId(200L);
            return e;
        });
        
        // First submit: creates the record
        service.submit(req);

        // Second call: in-memory cache blocks it, then looks up original via findBySignature
        when(outboundRepository.findBySignature(anyString())).thenReturn(Optional.of(savedEntity));
        
        // Act: second submit with same content+dest
        SubmitResponse resp = service.submit(req);

        // Assert
        assertEquals("200", resp.getMessageId());
        // save called once for first submit only
        verify(outboundRepository, times(1)).save(any(SmsOutboundEntity.class));
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

    @Test
    void testSubmit_FuzzyDedup_BlocksDifferentContent() throws Exception {
        // Arrange
        // Set normalization regex to ignore numbers
        java.lang.reflect.Field regexField = SubmissionService.class.getDeclaredField("dedupNormalizationRegex");
        regexField.setAccessible(true);
        regexField.set(service, "\\d+");

        SubmitRequest req1 = new SubmitRequest();
        req1.setMsisdn("0701234567");
        req1.setMessage("Your OTP is 12345");
        
        SubmitRequest req2 = new SubmitRequest();
        req2.setMsisdn("0701234567");
        req2.setMessage("Your OTP is 67890"); // Different content!
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // Mock save for first request
        when(outboundRepository.save(any(SmsOutboundEntity.class))).thenAnswer(invocation -> {
            SmsOutboundEntity e = invocation.getArgument(0);
            e.setId(500L);
            return e;
        });

        // First submit: normalized to "Your OTP is *"
        service.submit(req1);

        // Mock lookup for second request (it hits cache, then checks DB for original)
        SmsOutboundEntity original = new SmsOutboundEntity();
        original.setId(500L);
        original.setStatus("QUEUED");
        original.setSignature("dummy-signature"); // In real life, would be same hash
        original.setRequestId("orig-req-id"); // Prevent saving to generate a new requestId in toResponse
        // The service finds the signature in cache, so it blocks.
        // It then tries to find the original record by signature.
        // We need to mock that findBySignature call if we want a nice response.
        when(outboundRepository.findBySignature(anyString())).thenReturn(Optional.of(original));

        // Act: Second submit
        SubmitResponse resp = service.submit(req2);

        // Assert: Should be treated as duplicate even though content differs
        assertEquals("500", resp.getMessageId());
        // save called once for first submit only
        verify(outboundRepository, times(1)).save(any(SmsOutboundEntity.class));
    }

    @Test
    void testSubmit_Multipart_CreatesMultipleEntities() {
        // Arrange
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("0701234567");
        req.setClientMsgId("my-long-msg");
        // A long message to force splitting (e.g., 200 characters of English = 2 parts GSM)
        req.setMessage("A".repeat(200)); 
        
        when(router.resolve(anyString())).thenReturn(new String[]{"AWCC", "awcc:client"});
        
        // Mock save
        when(outboundRepository.save(any(SmsOutboundEntity.class))).thenAnswer(invocation -> {
            SmsOutboundEntity e = invocation.getArgument(0);
            e.setId(System.nanoTime()); // dummy unique id
            return e;
        });

        // Act
        SubmitResponse resp = service.submit(req);

        // Assert
        assertNotNull(resp);
        // Should return the request id as the messageId for multipart
        assertNotNull(resp.getMessageId());
        
        // Verify save was called 2 times (200 chars / 153 chars per part = 2 parts)
        org.mockito.ArgumentCaptor<SmsOutboundEntity> captor = org.mockito.ArgumentCaptor.forClass(SmsOutboundEntity.class);
        verify(outboundRepository, times(2)).save(captor.capture());
        
        java.util.List<SmsOutboundEntity> savedParts = captor.getAllValues();
        assertEquals(2, savedParts.size());
        
        SmsOutboundEntity part1 = savedParts.get(0);
        SmsOutboundEntity part2 = savedParts.get(1);
        
        assertEquals(1, part1.getPartNo());
        assertEquals(2, part1.getTotalParts());
        assertEquals("my-long-msg_p1", part1.getClientMsgId());
        assertNotNull(part1.getConcatRefNum());
        assertEquals(resp.getRequestId(), part1.getRequestId());
        
        assertEquals(2, part2.getPartNo());
        assertEquals(2, part2.getTotalParts());
        assertEquals("my-long-msg_p2", part2.getClientMsgId());
        assertEquals(part1.getConcatRefNum(), part2.getConcatRefNum());
        assertEquals(resp.getRequestId(), part2.getRequestId());
        
        assertEquals(part1.getSignature(), part2.getSignature()); // Signatures should be identical (hash of full message)
    }
}
