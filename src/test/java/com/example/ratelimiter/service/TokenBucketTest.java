package com.example.ratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ratelimiter.model.BucketDecision;
import com.example.ratelimiter.util.TimeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void acceptsRequestsWithinCapacityAndRejectsTheNextOne() {
        TokenBucket bucket = new TokenBucket(2, 1.0, new FakeTimeSource());

        assertTrue(bucket.tryConsume().accepted());
        assertEquals(0, bucket.tryConsume().remainingTokens());
        BucketDecision rejected = bucket.tryConsume();

        assertFalse(rejected.accepted());
        assertEquals(1, rejected.retryAfterSeconds());
    }

    @Test
    void refillsFractionallyAndNeverExceedsCapacity() {
        FakeTimeSource time = new FakeTimeSource();
        TokenBucket bucket = new TokenBucket(2, 2.0, time);
        bucket.tryConsume();
        bucket.tryConsume();

        time.advance(ONE_SECOND / 4);
        assertFalse(bucket.tryConsume().accepted());
        time.advance(ONE_SECOND / 4);
        assertTrue(bucket.tryConsume().accepted());
        time.advance(ONE_SECOND * 10);

        assertEquals(1, bucket.tryConsume().remainingTokens());
    }

    @Test
    void independentBucketsDoNotShareTokens() {
        FakeTimeSource time = new FakeTimeSource();
        TokenBucket first = new TokenBucket(1, 1.0, time);
        TokenBucket second = new TokenBucket(1, 1.0, time);

        assertTrue(first.tryConsume().accepted());
        assertFalse(first.tryConsume().accepted());
        assertTrue(second.tryConsume().accepted());
    }

    @Test
    void supportsCapacityOneAndLongIdleTime() {
        FakeTimeSource time = new FakeTimeSource();
        TokenBucket bucket = new TokenBucket(1, 0.25, time);
        assertTrue(bucket.tryConsume().accepted());

        time.advance(TimeUnit.DAYS.toNanos(365L * 100));

        BucketDecision decision = bucket.tryConsume();
        assertTrue(decision.accepted());
        assertEquals(0, decision.remainingTokens());
        assertEquals(4, decision.resetAfterSeconds());
    }

    @Test
    void roundsRetryAndResetUpForVerySmallRefillRate() {
        FakeTimeSource time = new FakeTimeSource();
        TokenBucket bucket = new TokenBucket(2, 0.0001, time);
        bucket.tryConsume();
        bucket.tryConsume();

        BucketDecision decision = bucket.tryConsume();

        assertEquals(10_000, decision.retryAfterSeconds());
        assertEquals(20_000, decision.resetAfterSeconds());
    }

    @Test
    void concurrentConsumersNeverExceedCapacity() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            TokenBucket bucket = new TokenBucket(50, 0.000000001, new FakeTimeSource());
            int threadCount = 200;
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                for (int index = 0; index < threadCount; index++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return bucket.tryConsume().accepted();
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int accepted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        accepted++;
                    }
                }
                assertEquals(50, accepted);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static final class FakeTimeSource implements TimeSource {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        private void advance(long durationNanos) {
            nanos.addAndGet(durationNanos);
        }
    }
}