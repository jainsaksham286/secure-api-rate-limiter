package com.example.ratelimiter.model;

import java.util.Map;

public record MonitoringSummary(
        long totalRequests,
        long acceptedRequests,
        long rejectedRequests,
        int activeClients,
        double rejectionRatePercent,
        long uptimeSeconds,
        long unknownClients,
        Map<String, Long> rejectionReasons) {
}