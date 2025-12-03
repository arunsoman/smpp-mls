package com.cascade.smppmls.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.smpp.SmppSessionManager;

/**
 * Test cases for MessageRerouterService
 */
@ExtendWith(MockitoExtension.class)
class MessageRerouterServiceTest {

    @Mock
    private SmsOutboundRepository outboundRepository;

    @Mock
    private SmppSessionManager sessionManager;

    @Mock
    private OperatorRouter operatorRouter;

    private MessageRerouterService rerouterService;

    @BeforeEach
    void setUp() {
        rerouterService = new MessageRerouterService(outboundRepository, sessionManager, operatorRouter);
    }

    @Test
    void testNoStoppedSessions_NoRerouting() {
        // Given: All sessions are active
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("awcc-primary-1", true);
        sessionHealth.put("awcc-primary-2", true);
        sessionHealth.put("awcc-primary-3", true);
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: No repository queries should be made
        verify(outboundRepository, never()).findByStatusAndSessionId(anyString(), anyString(), any(Pageable.class));
        verify(outboundRepository, never()).saveAll(anyList());
    }

    @Test
    void testStoppedSessionWithNoQueuedMessages() {
        // Given: One stopped session with no queued messages
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("awcc-primary-1", true);
        sessionHealth.put("awcc-primary-2", false); // Stopped
        sessionHealth.put("awcc-primary-3", true);
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("awcc-primary-2"), any(Pageable.class)))
            .thenReturn(Page.empty());
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: No messages should be saved
        verify(outboundRepository, never()).saveAll(anyList());
    }

    @Test
    void testRerouteMessagesFromStoppedSession() {
        // Given: Stopped session with queued messages
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("awcc-primary-1", true);
        sessionHealth.put("awcc-primary-2", false); // Stopped
        sessionHealth.put("awcc-primary-3", true);
        
        // Create queued messages
        List<SmsOutboundEntity> queuedMessages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SmsOutboundEntity msg = new SmsOutboundEntity();
            msg.setId((long) i);
            msg.setSessionId("awcc-primary-2");
            msg.setOperator("awcc");
            msg.setStatus("QUEUED");
            queuedMessages.add(msg);
        }
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("awcc-primary-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(queuedMessages));
        when(operatorRouter.getSessionsForOperator("awcc"))
            .thenReturn(List.of("awcc-primary-1", "awcc-primary-2", "awcc-primary-3"));
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: Messages should be reassigned
        ArgumentCaptor<List<SmsOutboundEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundRepository).saveAll(captor.capture());
        
        List<SmsOutboundEntity> savedMessages = captor.getValue();
        assertEquals(10, savedMessages.size());
        
        // Verify messages are distributed to active sessions only
        for (SmsOutboundEntity msg : savedMessages) {
            assertTrue(msg.getSessionId().equals("awcc-primary-1") || 
                      msg.getSessionId().equals("awcc-primary-3"));
            assertNotEquals("awcc-primary-2", msg.getSessionId()); // Not reassigned to stopped session
        }
    }

    @Test
    void testRoundRobinDistribution() {
        // Given: Stopped session with 6 messages, 2 active sessions
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("mtn-primary-1", true);
        sessionHealth.put("mtn-primary-2", false); // Stopped
        sessionHealth.put("mtn-primary-3", true);
        
        List<SmsOutboundEntity> queuedMessages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SmsOutboundEntity msg = new SmsOutboundEntity();
            msg.setId((long) i);
            msg.setSessionId("mtn-primary-2");
            msg.setOperator("mtn");
            msg.setStatus("QUEUED");
            queuedMessages.add(msg);
        }
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("mtn-primary-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(queuedMessages));
        when(operatorRouter.getSessionsForOperator("mtn"))
            .thenReturn(List.of("mtn-primary-1", "mtn-primary-2", "mtn-primary-3"));
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: Messages should be evenly distributed
        ArgumentCaptor<List<SmsOutboundEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundRepository).saveAll(captor.capture());
        
        List<SmsOutboundEntity> savedMessages = captor.getValue();
        
        // Count messages per session
        Map<String, Long> distribution = new HashMap<>();
        for (SmsOutboundEntity msg : savedMessages) {
            distribution.merge(msg.getSessionId(), 1L, Long::sum);
        }
        
        // Should be 3 messages each (6 messages / 2 active sessions)
        assertEquals(3L, distribution.get("mtn-primary-1"));
        assertEquals(3L, distribution.get("mtn-primary-3"));
    }

    @Test
    void testNoActiveSessionsAvailable() {
        // Given: All sessions for operator are stopped
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("awcc-primary-1", false);
        sessionHealth.put("awcc-primary-2", false); // Stopped with messages
        sessionHealth.put("awcc-primary-3", false);
        
        List<SmsOutboundEntity> queuedMessages = new ArrayList<>();
        SmsOutboundEntity msg = new SmsOutboundEntity();
        msg.setId(1L);
        msg.setSessionId("awcc-primary-2");
        msg.setOperator("awcc");
        msg.setStatus("QUEUED");
        queuedMessages.add(msg);
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("awcc-primary-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(queuedMessages));
        when(operatorRouter.getSessionsForOperator("awcc"))
            .thenReturn(List.of("awcc-primary-1", "awcc-primary-2", "awcc-primary-3"));
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: No messages should be saved (no active sessions to reroute to)
        verify(outboundRepository, never()).saveAll(anyList());
    }

    @Test
    void testMessagesWithUnknownOperatorSkipped() {
        // Given: Messages with null operator
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("test-session-1", true);
        sessionHealth.put("test-session-2", false); // Stopped
        
        List<SmsOutboundEntity> queuedMessages = new ArrayList<>();
        SmsOutboundEntity msg1 = new SmsOutboundEntity();
        msg1.setId(1L);
        msg1.setSessionId("test-session-2");
        msg1.setOperator(null); // Unknown operator
        msg1.setStatus("QUEUED");
        queuedMessages.add(msg1);
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("test-session-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(queuedMessages));
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: No messages should be saved (unknown operator)
        verify(outboundRepository, never()).saveAll(anyList());
    }

    @Test
    void testMultipleStoppedSessions() {
        // Given: Two stopped sessions with messages
        Map<String, Boolean> sessionHealth = new HashMap<>();
        sessionHealth.put("awcc-primary-1", true);
        sessionHealth.put("awcc-primary-2", false); // Stopped
        sessionHealth.put("mtn-primary-1", true);
        sessionHealth.put("mtn-primary-2", false); // Stopped
        
        // AWCC messages
        List<SmsOutboundEntity> awccMessages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SmsOutboundEntity msg = new SmsOutboundEntity();
            msg.setId((long) i);
            msg.setSessionId("awcc-primary-2");
            msg.setOperator("awcc");
            msg.setStatus("QUEUED");
            awccMessages.add(msg);
        }
        
        // MTN messages
        List<SmsOutboundEntity> mtnMessages = new ArrayList<>();
        for (int i = 5; i < 10; i++) {
            SmsOutboundEntity msg = new SmsOutboundEntity();
            msg.setId((long) i);
            msg.setSessionId("mtn-primary-2");
            msg.setOperator("mtn");
            msg.setStatus("QUEUED");
            mtnMessages.add(msg);
        }
        
        when(sessionManager.getSessionHealth()).thenReturn(sessionHealth);
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("awcc-primary-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(awccMessages));
        when(outboundRepository.findByStatusAndSessionId(eq("QUEUED"), eq("mtn-primary-2"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(mtnMessages));
        when(operatorRouter.getSessionsForOperator("awcc"))
            .thenReturn(List.of("awcc-primary-1", "awcc-primary-2"));
        when(operatorRouter.getSessionsForOperator("mtn"))
            .thenReturn(List.of("mtn-primary-1", "mtn-primary-2"));
        
        // When
        rerouterService.rerouteStoppedSessionMessages();
        
        // Then: Both sets of messages should be rerouted
        verify(outboundRepository, times(2)).saveAll(anyList());
    }
}
