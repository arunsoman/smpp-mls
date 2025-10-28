package com.cascade.smppmls.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "smpp")
public class SmppProperties {

    private Default defaultConfig = new Default();
    private Map<String, Operator> operators;

    public Default getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(Default defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, Operator> getOperators() {
        return operators;
    }

    public void setOperators(Map<String, Operator> operators) {
        this.operators = operators;
    }

    public static class Default {
        private String systemType = "OTA";
        private int enquireLinkInterval = 30000;
        private int reconnectDelay = 5000;
        private int windowSize = 100;

        public String getSystemType() {
            return systemType;
        }

        public void setSystemType(String systemType) {
            this.systemType = systemType;
        }

        public int getEnquireLinkInterval() {
            return enquireLinkInterval;
        }

        public void setEnquireLinkInterval(int enquireLinkInterval) {
            this.enquireLinkInterval = enquireLinkInterval;
        }

        public int getReconnectDelay() {
            return reconnectDelay;
        }

        public void setReconnectDelay(int reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }
    }

    public static class Operator {
        private String host;
        private int port = 2775;
        private List<Session> sessions;
        private List<String> prefixes;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public List<Session> getSessions() {
            return sessions;
        }

        public void setSessions(List<Session> sessions) {
            this.sessions = sessions;
        }

        public List<String> getPrefixes() {
            return prefixes;
        }

        public void setPrefixes(List<String> prefixes) {
            this.prefixes = prefixes;
        }
    }

    public static class Session {
        private String systemId;
        private String password;
        private int tps = 100;

        public String getSystemId() {
            return systemId;
        }

        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getTps() {
            return tps;
        }

        public void setTps(int tps) {
            this.tps = tps;
        }
    }
}
