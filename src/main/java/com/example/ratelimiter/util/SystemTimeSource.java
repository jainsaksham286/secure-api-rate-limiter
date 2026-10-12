package com.example.ratelimiter.util;

import org.springframework.stereotype.Component;

@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}