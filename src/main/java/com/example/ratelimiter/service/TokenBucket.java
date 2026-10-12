package com.example.ratelimiter.service;

import com.example.ratelimiter.model.BucketDecision;
import com.example.ratelimiter.util.TimeSource;
import java.util.concurrent.locks.ReentrantLock;

/** A per-client token bucket; one lock makes refill and token consumption a single atomic operation. */
public final class TokenBucket {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double ROUNDING_EPSILON = 1.0e-12;

    private final int capacity;
    private final double refillTokensPerSecond;
    private final TimeSource timeSource;
    private final ReentrantLock lock = new ReentrantLock();
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, double refillTokensPerSecond, TimeSource timeSource) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (!Double.isFinite(refillTokensPerSecond) || refillTokensPerSecond <= 0.0) {
            throw new IllegalArgumentException("refill rate must be finite and positive");
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.timeSource = timeSource;
        this.tokens = capacity;
        this.lastRefillNanos = timeSource.nanoTime();
    }

    public BucketDecision tryConsume() {
        lock.lock();
        try {
            refill(timeSource.nanoTime());
            boolean accepted = tokens + ROUNDING_EPSILON >= 1.0;
            if (accepted) {
                tokens = Math.max(0.0, tokens - 1.0);
            }
            int remaining = Math.min(capacity, (int) Math.floor(tokens + ROUNDING_EPSILON));
            long retryAfter = accepted ? 0 : secondsUntil(1.0 - tokens);
            long resetAfter = secondsUntil(capacity - tokens);
            return new BucketDecision(accepted, capacity, remaining, resetAfter, retryAfter);
        } finally {
            lock.unlock();
        }
    }

    public int currentRemainingTokens() {
        lock.lock();
        try {
            refill(timeSource.nanoTime());
            return Math.min(capacity, (int) Math.floor(tokens + ROUNDING_EPSILON));
        } finally {
            lock.unlock();
        }
    }

    private void refill(long nowNanos) {
        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos > 0 && tokens < capacity) {
            double addedTokens = elapsedNanos / NANOS_PER_SECOND * refillTokensPerSecond;
            tokens = Math.min(capacity, tokens + addedTokens);
        }
        lastRefillNanos = nowNanos;
    }

    private long secondsUntil(double tokensNeeded) {
        if (tokensNeeded <= ROUNDING_EPSILON) {
            return 0;
        }
        double seconds = Math.ceil(tokensNeeded / refillTokensPerSecond);
        return seconds >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) seconds;
    }
}