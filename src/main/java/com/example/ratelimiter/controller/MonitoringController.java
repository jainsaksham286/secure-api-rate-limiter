package com.example.ratelimiter.controller;

import com.example.ratelimiter.model.ClientStatistics;
import com.example.ratelimiter.model.MonitoringSummary;
import com.example.ratelimiter.model.TimeSeriesResponse;
import com.example.ratelimiter.service.RateLimiterService;
import com.example.ratelimiter.service.StatsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {
    private final StatsService statsService;
    private final RateLimiterService rateLimiterService;

    public MonitoringController(StatsService statsService, RateLimiterService rateLimiterService) {
        this.statsService = statsService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/summary")
    public MonitoringSummary summary() {
        return statsService.summary();
    }

    @GetMapping("/clients")
    public List<ClientStatistics> clients() {
        return statsService.clientStatistics(rateLimiterService);
    }

    @GetMapping("/timeseries")
    public TimeSeriesResponse timeSeries() {
        return statsService.timeSeries();
    }
}