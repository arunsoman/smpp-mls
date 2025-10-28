package com.cascade.smppmls.smpp;

import com.cascade.smppmls.config.SmppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocketSmppSessionManagerTest {

    @Mock
    private SmppProperties smppProperties;
    
    @Mock
    private SmppSessionConfig smppSessionConfig;
    
    private SocketSmppSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        when(smppProperties.getSessions()).thenReturn(Collections.singletonMap("test", smppSessionConfig));
        when(smppSessionConfig.getHost()).thenReturn("localhost");
        when(smppSessionConfig.getPort()).thenReturn(2775);
        
        sessionManager = new SocketSmppSessionManager(smppProperties);
    }

    @Test
    void testInitializeSessions() {
        // This test is currently basic and will need to be expanded
        // once we implement proper SMPP session handling with mocks
        assertDoesNotThrow(() -> sessionManager.initializeSessions());
    }
}
