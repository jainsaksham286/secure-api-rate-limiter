package com.example.ratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ratelimiter.model.MonitoringSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StatsServiceConcurrencyTest {
    @Test
    void recordsAcceptedAndRejectedCountersExactlyAcrossThreads() throws Exception {
        int threadCount = 32;
        int eventsPerThread = 500;
        long expectedPerOutcome = (long) threadCount * eventsPerThread;
        StatsService statsService = new StatsService(() -> 0L);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>(threadCount);

        try {
            for (int thread = 0; thread < threadCount; thread++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent events");
                    }
                    for (int event = 0; event < eventsPerThread; event++) {
                        statsService.recordAccepted("demo-free");
                        statsService.recordRejected("demo-free", "rate_limited");
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        MonitoringSummary summary = statsService.summary();
        assertEquals(expectedPerOutcome * 2, summary.totalRequests());
        assertEquals(expectedPerOutcome, summary.acceptedRequests());
        assertEquals(expectedPerOutcome, summary.rejectedRequests());
        assertEquals(1, summary.activeClients());
        assertEquals(expectedPerOutcome, summary.rejectionReasons().get("rate_limited"));
        assertEquals(0L, summary.rejectionReasons().get("missing_key"));
        assertEquals(0L, summary.rejectionReasons().get("invalid_key"));
    }
}
