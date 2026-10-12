package com.example.ratelimiter.service;

import com.example.ratelimiter.model.ClientStatistics;
import com.example.ratelimiter.model.MonitoringSummary;
import com.example.ratelimiter.model.TimeSeriesResponse;
import com.example.ratelimiter.util.TimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Service;

/** Tracks bounded aggregate and configured-client statistics without retaining unknown API keys. */
@Service
public class StatsService {
    private static final long BUCKET_NANOS = 5_000_000_000L;
    private static final int BUCKET_COUNT = 60;

    private final TimeSource timeSource;
    private final long startedAtNanos;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder missingKeys = new LongAdder();
    private final LongAdder invalidKeys = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final ConcurrentHashMap<String, ClientCounters> clients = new ConcurrentHashMap<>();
    private final TimeSeriesBucket[] timeSeries = new TimeSeriesBucket[BUCKET_COUNT];

    public StatsService(TimeSource timeSource) {
        this.timeSource = timeSource;
        this.startedAtNanos = timeSource.nanoTime();
        for (int index = 0; index < timeSeries.length; index++) {
            timeSeries[index] = new TimeSeriesBucket();
        }
    }

    public void recordAccepted(String clientId) {
        accepted.increment();
        recordClient(clientId, true);
        recordTimeSeries(true);
    }

    public void recordRejected(String clientId, String reason) {
        rejected.increment();
        switch (reason) {
            case "missing_key" -> missingKeys.increment();
            case "invalid_key" -> invalidKeys.increment();
            case "rate_limited" -> rateLimited.increment();
            default -> throw new IllegalArgumentException("Unsupported rejection reason");
        }
        recordClient(clientId, false);
        recordTimeSeries(false);
    }

    public MonitoringSummary summary() {
        long acceptedCount = accepted.sum();
        long rejectedCount = rejected.sum();
        long total = acceptedCount + rejectedCount;
        int activeClients = (int) clients.values().stream().filter(counter -> counter.total() > 0).count();
        Map<String, Long> reasons = new LinkedHashMap<>();
        reasons.put("rate_limited", rateLimited.sum());
        reasons.put("missing_key", missingKeys.sum());
        reasons.put("invalid_key", invalidKeys.sum());
        return new MonitoringSummary(
                total,
                acceptedCount,
                rejectedCount,
                activeClients,
                rejectionRate(acceptedCount, rejectedCount),
                Math.max(0, (timeSource.nanoTime() - startedAtNanos) / 1_000_000_000L),
                invalidKeys.sum(),
                reasons);
    }

    public List<ClientStatistics> clientStatistics(RateLimiterService rateLimiterService) {
        return clients.entrySet().stream()
                .map(entry -> {
                    ClientCounters counters = entry.getValue();
                    RateLimiterService.ClientBucket configured = rateLimiterService.findByClientId(entry.getKey());
                    int remaining = configured == null ? 0 : configured.bucket().currentRemainingTokens();
                    long acceptedCount = counters.accepted.sum();
                    long rejectedCount = counters.rejected.sum();
                    return new ClientStatistics(
                            entry.getKey(), counters.total(), acceptedCount, rejectedCount,
                            rejectionRate(acceptedCount, rejectedCount), remaining, counters.lastRequestTime);
                })
                .sorted(Comparator.comparing(ClientStatistics::clientId))
                .toList();
    }

    public synchronized TimeSeriesResponse timeSeries() {
        long currentBucket = Math.floorDiv(timeSource.nanoTime(), BUCKET_NANOS);
        long currentEpochBucket = Instant.now().getEpochSecond() / 5 * 5;
        List<TimeSeriesResponse.Point> points = new ArrayList<>(BUCKET_COUNT);
        for (int offset = BUCKET_COUNT - 1; offset >= 0; offset--) {
            long bucketNumber = currentBucket - offset;
            TimeSeriesBucket bucket = timeSeries[Math.floorMod(bucketNumber, BUCKET_COUNT)];
            boolean present = bucket.bucketNumber == bucketNumber;
            points.add(new TimeSeriesResponse.Point(
                    currentEpochBucket - offset * 5L,
                    present ? bucket.accepted : 0,
                    present ? bucket.rejected : 0));
        }
        return new TimeSeriesResponse(5, points);
    }

    private void recordClient(String clientId, boolean isAccepted) {
        if (clientId == null) {
            return;
        }
        ClientCounters counters = clients.computeIfAbsent(clientId, ignored -> new ClientCounters());
        if (isAccepted) {
            counters.accepted.increment();
        } else {
            counters.rejected.increment();
        }
        counters.lastRequestTime = Instant.now();
    }

    private synchronized void recordTimeSeries(boolean isAccepted) {
        long bucketNumber = Math.floorDiv(timeSource.nanoTime(), BUCKET_NANOS);
        TimeSeriesBucket bucket = timeSeries[Math.floorMod(bucketNumber, BUCKET_COUNT)];
        if (bucket.bucketNumber != bucketNumber) {
            bucket.bucketNumber = bucketNumber;
            bucket.accepted = 0;
            bucket.rejected = 0;
        }
        if (isAccepted) {
            bucket.accepted++;
        } else {
            bucket.rejected++;
        }
    }

    private static double rejectionRate(long acceptedCount, long rejectedCount) {
        long total = acceptedCount + rejectedCount;
        return total == 0 ? 0.0 : Math.round(rejectedCount * 10_000.0 / total) / 100.0;
    }

    private static final class ClientCounters {
        private final LongAdder accepted = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private volatile Instant lastRequestTime;

        private long total() {
            return accepted.sum() + rejected.sum();
        }
    }

    private static final class TimeSeriesBucket {
        private long bucketNumber = Long.MIN_VALUE;
        private long accepted;
        private long rejected;
    }
}