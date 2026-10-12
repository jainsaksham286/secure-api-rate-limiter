package com.example.ratelimiter.model;

import java.time.Instant;

public record ClientStatistics(
        String clientId,
        long total,
        long accepted,
        long rejected,
        double rejectionRatePercent,
        int currentTokensRemaining,
        Instant lastRequestTime) {
}