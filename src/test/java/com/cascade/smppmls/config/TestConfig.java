package com.cascade.smppmls.config;

import com.cascade.smppmls.smpp.SmppProperties;
import com.cascade.smppmls.smpp.SmppSessionConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

@TestConfiguration
public class TestConfig {

    @Bean
    public SmppProperties smppProperties() {
        SmppProperties properties = new SmppProperties();
        
        // Create test session config
        SmppSessionConfig sessionConfig = new SmppSessionConfig();
        sessionConfig.setHost("localhost");
        sessionConfig.setPort(2775);
        sessionConfig.setSystemId("test");
        sessionConfig.setPassword("password");
        
        // Add to sessions map
        Map<String, SmppSessionConfig> sessions = new HashMap<>();
        sessions.put("test-session", sessionConfig);
        properties.setSessions(sessions);
        
        return properties;
    }
}
