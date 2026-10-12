package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.model.BucketDecision;
import com.example.ratelimiter.util.TimeSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {
    private final Map<String, ClientBucket> clientsByApiKey = new HashMap<>();

    public RateLimiterService(RateLimiterProperties properties, TimeSource timeSource) {
        properties.getClients().forEach((name, client) -> {
            if (client.getClientId() == null || client.getClientId().isBlank()
                    || client.getApiKey() == null || client.getApiKey().isBlank()) {
                throw new IllegalArgumentException("Each configured client requires a clientId and apiKey");
            }
            TokenBucket bucket = new TokenBucket(client.getCapacity(), client.getRefillTokensPerSecond(), timeSource);
            if (clientsByApiKey.put(client.getApiKey(), new ClientBucket(name, client, bucket)) != null) {
                throw new IllegalArgumentException("Configured API keys must be unique");
            }
        });
    }

    public ClientBucket findClient(String apiKey) {
        return apiKey == null ? null : clientsByApiKey.get(apiKey);
    }

    public ClientBucket findByClientId(String clientId) {
        return clientsByApiKey.values().stream()
                .filter(client -> client.clientId().equals(clientId))
                .findFirst()
                .orElse(null);
    }

    public List<ClientBucket> configuredClients() {
        return List.copyOf(clientsByApiKey.values());
    }

    public record ClientBucket(String name, RateLimiterProperties.Client configuration, TokenBucket bucket) {
        public String clientId() {
            return configuration.getClientId();
        }

        public BucketDecision tryConsume() {
            return bucket.tryConsume();
        }
    }
}