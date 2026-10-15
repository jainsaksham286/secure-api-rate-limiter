package com.example.ratelimiter.util;

/**
 * Supplies monotonic time so elapsed-time calculations are unaffected by
 * wall-clock changes.
 */
public interface TimeSource {
    long nanoTime();
}