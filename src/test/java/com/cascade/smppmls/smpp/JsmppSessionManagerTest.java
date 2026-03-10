package com.cascade.smppmls.smpp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.OptionalParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.cascade.smppmls.config.SmppProperties;
import com.cascade.smppmls.entity.SmsDlrEntity;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsDlrRepository;
import com.cascade.smppmls.repository.SmsOutboundRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@ExtendWith(MockitoExtension.class)
class JsmppSessionManagerTest {

    @Mock
    private SmppProperties smppProperties;
    @Mock
    private SmsOutboundRepository outboundRepository;
    @Mock
    private SmsDlrRepository dlrRepository;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private Counter mockCounter;

    @InjectMocks
    private JsmppSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        // leniency for tests using counter
        lenient().when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(mockCounter);
    }

    @Test
    void testProcessDeliveryReceipt_SinglePart() throws Exception {
        // Arrange
        DeliverSm deliverSm = new DeliverSm();
        // simulate standard DLR text
        String dlrText = "id:abc-123 sub:001 dlvrd:001 submit date:2310101200 done date:2310101201 stat:DELIVRD err:000 text:hello";
        deliverSm.setShortMessage(dlrText.getBytes(StandardCharsets.UTF_8));

        SmsOutboundEntity outbound = new SmsOutboundEntity();
        outbound.setId(10L);
        when(outboundRepository.findBySmscMsgId("abc-123")).thenReturn(outbound);

        // Put a dummy session to map the incoming requests
        // Reflection to inject a dummy session key since session hash map is private
        java.lang.reflect.Field sessionsField = JsmppSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, org.jsmpp.session.SMPPSession> sessions =
            (java.util.Map<String, org.jsmpp.session.SMPPSession>) sessionsField.get(sessionManager);
        sessions.put("dummy:session", mock(org.jsmpp.session.SMPPSession.class));

        // Act
        sessionManager.onAcceptDeliverSm(deliverSm);

        // Assert
        // Check finding by smsc ID
        verify(outboundRepository).findBySmscMsgId("abc-123");
        // Check updating main record
        verify(outboundRepository).save(argThat(entity -> "DELIVERED".equals(entity.getStatus())));
        // Check inserting DLR record
        verify(dlrRepository).save(any(SmsDlrEntity.class));
    }

    @Test
    void testProcessDeliveryReceipt_MultipartAggregatesToFailed() throws Exception {
        // Arrange
        DeliverSm deliverSm = new DeliverSm();
        String dlrText = "id:multi-p1 stat:UNDELIV";
        deliverSm.setShortMessage(dlrText.getBytes(StandardCharsets.UTF_8));

        SmsOutboundEntity part1 = new SmsOutboundEntity();
        part1.setId(21L);
        part1.setRequestId("multi-req-1");
        part1.setTotalParts(2);
        
        SmsOutboundEntity part2 = new SmsOutboundEntity();
        part2.setId(22L);
        part2.setRequestId("multi-req-1");
        part2.setStatus("DELIVERED"); // second part delivered
        part2.setTotalParts(2);

        when(outboundRepository.findBySmscMsgId("multi-p1")).thenReturn(part1);
        when(outboundRepository.findAllByRequestId("multi-req-1")).thenReturn(List.of(part1, part2));

        // Let's inject a session
        java.lang.reflect.Field sessionsField = JsmppSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, org.jsmpp.session.SMPPSession> sessions =
            (java.util.Map<String, org.jsmpp.session.SMPPSession>) sessionsField.get(sessionManager);
        sessions.put("dummy-multi:session", mock(org.jsmpp.session.SMPPSession.class));

        // Act - receive failure for part 1
        sessionManager.onAcceptDeliverSm(deliverSm);

        // Assert
        verify(outboundRepository).findBySmscMsgId("multi-p1");
        verify(outboundRepository).save(argThat(entity -> "UNDELIVERABLE".equals(entity.getStatus())));
        
        // Counter should have been incremented for group failure
        verify(meterRegistry).counter("smpp.outbound.group.failed", "session", "dummy-multi:session");
        verify(mockCounter).increment(); // The part 1 failure trips the group failure
    }
}
