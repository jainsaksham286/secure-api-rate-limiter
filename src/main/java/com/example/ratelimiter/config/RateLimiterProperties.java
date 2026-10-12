package com.example.ratelimiter.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimiterProperties {
    private Map<String, Client> clients = new LinkedHashMap<>();

    public Map<String, Client> getClients() {
        return clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients;
    }

    public static class Client {
        private String clientId;
        private String apiKey;
        private int capacity;
        private double refillTokensPerSecond;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillTokensPerSecond() {
            return refillTokensPerSecond;
        }

        public void setRefillTokensPerSecond(double refillTokensPerSecond) {
            this.refillTokensPerSecond = refillTokensPerSecond;
        }
    }
}