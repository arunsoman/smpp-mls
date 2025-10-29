package com.cascade.smppmls.config;

import java.util.List;
import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "smpp")
public class SmppProperties {

    private Default defaultConfig = new Default();
    private Map<String, Operator> operators;

    @Data
    public static class Default {
        private String systemType = "OTA";
        private int enquireLinkInterval = 30000;
        private int reconnectDelay = 5000;
        private int windowSize = 100;
    }

    @Data
    public static class Operator {
        private String host;
        private int port = 2775;
        private List<Session> sessions;
        private List<String> prefixes;
    }

    @Data
    public static class Session {
        private String uuId;
        private String systemId;
        private String password;
        private String systemType;
        private String serviceType = ""; // Default empty service type
        private String sourceAddress = ""; // Default source address (originator)
        private int tps = 100;
    }
}
