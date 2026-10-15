package com.example.ratelimiter.controller;

import com.example.ratelimiter.filter.ApiSecurityFilter;
import com.example.ratelimiter.model.BucketDecision;
import com.example.ratelimiter.service.RateLimiterService;
import com.example.ratelimiter.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {
    private final StatsService statsService;

    public DemoController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/demo/public")
    public Map<String, Object> publicDemo() {
        statsService.recordAccepted("public");
        return Map.of("timestamp", Instant.now(), "message", "Public demo endpoint (unlimited, counted)");
    }

    @GetMapping("/api/demo/data")
    public DemoData data(HttpServletRequest request) {
        RateLimiterService.ClientBucket client = (RateLimiterService.ClientBucket) request
                .getAttribute(ApiSecurityFilter.CLIENT_ATTRIBUTE);
        BucketDecision decision = (BucketDecision) request.getAttribute(ApiSecurityFilter.DECISION_ATTRIBUTE);
        return new DemoData(Instant.now(), client.clientId(), decision.remainingTokens(),
                Map.of("recordId", "sample-001", "classification", "demo", "message", "Protected sample data"));
    }

    public record DemoData(Instant timestamp, String clientId, int remainingTokens, Map<String, String> samplePayload) {
    }
}