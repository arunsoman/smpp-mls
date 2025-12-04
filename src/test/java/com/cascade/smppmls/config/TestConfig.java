package com.cascade.smppmls.config;

import com.cascade.smppmls.config.SmppProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@TestConfiguration
public class TestConfig {

    @Bean
    public SmppProperties smppProperties() {
        SmppProperties properties = new SmppProperties();
        
        // Create test session config
        SmppProperties.Session session = new SmppProperties.Session();
        session.setSystemId("test");
        session.setPassword("password");
        session.setSystemType("OTA");
        session.setTps(10);
        
        // Create operator config
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setHost("localhost");
        operator.setPort(2775);
        operator.setSessions(Collections.singletonList(session));
        
        // Add to operators map
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        operators.put("test-operator", operator);
        properties.setOperators(operators);
        
        return properties;
    }
}
