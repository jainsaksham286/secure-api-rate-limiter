package com.example.ratelimiter.model;

public record BucketDecision(
        boolean accepted,
        int capacity,
        int remainingTokens,
        long resetAfterSeconds,
        long retryAfterSeconds) {
}