package com.cascade.smppmls.smpp;

import com.cascade.smppmls.config.SmppProperties;
import com.cascade.smppmls.repository.SmsDlrRepository;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocketSmppSessionManagerTest {

    @Mock
    private SmppProperties smppProperties;
    
    private SocketSmppSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SocketSmppSessionManager(smppProperties);
    }

    @Test
    void testStartWithNoOperators() {
        when(smppProperties.getOperators()).thenReturn(Collections.emptyMap());
        assertDoesNotThrow(() -> sessionManager.start());
    }

    @Test
    void testStartWithOperators() {
        // Setup mock properties
        SmppProperties.Session session = new SmppProperties.Session();
        session.setSystemId("test");
        session.setPassword("password");
        session.setTps(10);
        
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setHost("localhost");
        operator.setPort(2775);
        operator.setSessions(Collections.singletonList(session));
        
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        operators.put("test-operator", operator);
        
        SmppProperties.Default defaultConfig = new SmppProperties.Default();
        defaultConfig.setEnquireLinkInterval(30000);
        defaultConfig.setReconnectDelay(5000);

        lenient().when(smppProperties.getOperators()).thenReturn(operators);
        lenient().when(smppProperties.getDefaultConfig()).thenReturn(defaultConfig);

        // We can't easily test the actual connection logic without a real SMPP server or complex mocking of the socket
        // So we just verify that start() doesn't throw exceptions during initialization
        assertDoesNotThrow(() -> sessionManager.start());
        
        // Cleanup
        sessionManager.stop();
    }
}
