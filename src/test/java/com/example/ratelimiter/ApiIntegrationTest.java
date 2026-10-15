    package com.example.ratelimiter;

    import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
    import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
    import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
    import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertFalse;
    import static org.junit.jupiter.api.Assertions.assertTrue;

    import com.example.ratelimiter.util.TimeSource;
    import java.util.ArrayList;
    import java.util.List;
    import java.util.concurrent.TimeUnit;
    import java.util.concurrent.CountDownLatch;
    import java.util.concurrent.ExecutorService;
    import java.util.concurrent.Executors;
    import java.util.concurrent.Future;
    import java.util.concurrent.atomic.AtomicInteger;
    import java.util.concurrent.atomic.AtomicLong;
    import org.junit.jupiter.api.Test;
    import org.springframework.beans.factory.annotation.Autowired;
    import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
    import org.springframework.boot.test.context.SpringBootTest;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Primary;
    import org.springframework.context.annotation.Import;
    import org.springframework.test.web.servlet.MockMvc;
    import org.springframework.test.web.servlet.MvcResult;
    import org.springframework.test.annotation.DirtiesContext;
    import org.springframework.boot.test.context.TestConfiguration;

    @SpringBootTest(properties = {
            "app.rate-limit.clients.demo-free.capacity=2",
            "app.rate-limit.clients.demo-free.refill-tokens-per-second=1.0"
    })
    @AutoConfigureMockMvc
    @Import(ApiIntegrationTest.TestClockConfiguration.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    class ApiIntegrationTest {
        private static final String FREE_KEY = "demo-key-free-123";
        private static final String ADMIN_KEY = "fake-admin-key-123";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private FakeTimeSource timeSource;

        @Test
        void publicEndpointIsAccessibleWithoutAKey() throws Exception {
            String response = mockMvc.perform(get("/api/demo/public"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-Id"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(response);
        }

        @Test
        void missingApiKeyIsRejectedWith401() throws Exception {
            String response = mockMvc.perform(get("/api/demo/data"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Authentication failed"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(response);
        }

        @Test
        void invalidApiKeyIsRejectedWith401() throws Exception {
            String response = mockMvc.perform(get("/api/demo/data").header("X-API-Key", "attacker-controlled-key"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication failed"))
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(response);
        }

        @Test
        void successfulRequestsIncludeRateLimitHeaders() throws Exception {
            MvcResult firstAccepted = mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "2"))
                    .andExpect(header().string("X-RateLimit-Remaining", "1"))
                    .andExpect(header().exists("X-RateLimit-Reset"))
                    .andReturn();
            MvcResult secondAccepted = mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andReturn();
            assertDoesNotExposeSecrets(firstAccepted.getResponse().getContentAsString());
            assertDoesNotExposeSecrets(secondAccepted.getResponse().getContentAsString());
        }

        @Test
        void exhaustedBucketReturns429WithRetryAfter() throws Exception {
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andExpect(jsonPath("$.error").value("Too Many Requests"));
        }

        @Test
        void monitoringEndpointsRequireAdminKey() throws Exception {
            mockMvc.perform(get("/api/monitoring/summary"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication failed"));
        }

        @Test
        void monitoringEndpointsRejectWrongAdminKey() throws Exception {
            mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", "wrong-admin-key"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void monitoringSummaryReportsRequestCountsAndReasons() throws Exception {
            mockMvc.perform(get("/api/demo/data"));
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", "attacker-controlled-key"));
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests());

            String summary = mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", ADMIN_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRequests").value(5))
                    .andExpect(jsonPath("$.acceptedRequests").value(2))
                    .andExpect(jsonPath("$.rejectedRequests").value(3))
                    .andExpect(jsonPath("$.activeClients").value(1))
                    .andExpect(jsonPath("$.unknownClients").value(1))
                    .andExpect(jsonPath("$.rejectionReasons.missing_key").value(1))
                    .andExpect(jsonPath("$.rejectionReasons.invalid_key").value(1))
                    .andExpect(jsonPath("$.rejectionReasons.rate_limited").value(1))
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(summary);
        }

        @Test
        void clientsEndpointReportsClientStatistics() throws Exception {
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests());

            String clients = mockMvc.perform(get("/api/monitoring/clients").header("X-Admin-Key", ADMIN_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].clientId").value("demo-free"))
                    .andExpect(jsonPath("$[0].total").value(3))
                    .andExpect(jsonPath("$[0].accepted").value(2))
                    .andExpect(jsonPath("$[0].rejected").value(1))
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(clients);
            assertFalse(clients.contains("apiKey"));
        }

        @Test
        void timeSeriesEndpointReportsCurrentBucketCounts() throws Exception {
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests());

            String timeSeries = mockMvc.perform(get("/api/monitoring/timeseries").header("X-Admin-Key", ADMIN_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bucketSeconds").value(5))
                    .andExpect(jsonPath("$.points.length()").value(60))
                    .andExpect(jsonPath("$.points[59].accepted").value(2))
                    .andExpect(jsonPath("$.points[59].rejected").value(1))
                    .andReturn().getResponse().getContentAsString();
            assertDoesNotExposeSecrets(timeSeries);
        }

        @Test
        void freeBucketRefillsAfterAdvancingClock() throws Exception {
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests());
            timeSource.advance(TimeUnit.SECONDS.toNanos(1));
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));

            mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", ADMIN_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRequests").value(4))
                    .andExpect(jsonPath("$.acceptedRequests").value(3))
                    .andExpect(jsonPath("$.rejectedRequests").value(1));
        }

        @Test
        void freeAndProClientsHaveIndependentHttpLimits() throws Exception {
            requestFreeData();
            requestFreeData();
            mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                    .andExpect(status().isTooManyRequests());

            mockMvc.perform(get("/api/demo/data").header("X-API-Key", "demo-key-pro-456"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clientId").value("demo-pro"));
        }

        @Test
        void concurrentFreeKeyRequestsAreCountedExactly() throws Exception {
            int requestCount = 100;
            int workerCount = 32;
            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            CountDownLatch workersReady = new CountDownLatch(workerCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger readyWorkers = new AtomicInteger();
            List<Future<Integer>> responses = new ArrayList<>(requestCount);
            try {
                for (int index = 0; index < requestCount; index++) {
                    responses.add(executor.submit(() -> {
                        if (readyWorkers.getAndIncrement() < workerCount) {
                            workersReady.countDown();
                        }
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to start concurrent requests");
                        }
                        return mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                                .andReturn().getResponse().getStatus();
                    }));
                }

                assertTrue(workersReady.await(10, TimeUnit.SECONDS));
                start.countDown();
                int accepted = 0;
                int rejected = 0;
                for (Future<Integer> response : responses) {
                    int responseStatus = response.get(30, TimeUnit.SECONDS);
                    if (responseStatus == 200) {
                        accepted++;
                    } else if (responseStatus == 429) {
                        rejected++;
                    } else {
                        throw new AssertionError("Unexpected response status: " + responseStatus);
                    }
                }

                assertEquals(2, accepted);
                assertEquals(98, rejected);
                mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", ADMIN_KEY))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalRequests").value(100))
                        .andExpect(jsonPath("$.acceptedRequests").value(2))
                        .andExpect(jsonPath("$.rejectedRequests").value(98));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void monitoringResponsesNeverExposeKeysOrApiKeyField() throws Exception {
            List<String> responses = List.of(
                    monitoringResponse("/api/monitoring/summary"),
                    monitoringResponse("/api/monitoring/clients"),
                    monitoringResponse("/api/monitoring/timeseries"));

            for (String response : responses) {
                assertFalse(response.contains("demo-key-"));
                assertFalse(response.contains(ADMIN_KEY));
            }
            assertFalse(responses.get(1).contains("apiKey"));
        }

        @Test
        void healthEndpointIsUp() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        private MvcResult requestFreeData() throws Exception {
            return mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY)).andReturn();
        }

        private String monitoringResponse(String endpoint) throws Exception {
            return mockMvc.perform(get(endpoint).header("X-Admin-Key", ADMIN_KEY))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }

        private static void assertDoesNotExposeSecrets(String response) {
            assertFalse(response.contains(FREE_KEY));
            assertFalse(response.contains(ADMIN_KEY));
        }

        @TestConfiguration
        static class TestClockConfiguration {
            @Bean
            @Primary
            FakeTimeSource fakeTimeSource() {
                return new FakeTimeSource();
            }
        }

        static class FakeTimeSource implements TimeSource {
            private final AtomicLong nanos = new AtomicLong();

            @Override
            public long nanoTime() {
                return nanos.get();
            }

            void advance(long amountNanos) {
                nanos.addAndGet(amountNanos);
            }
        }
    }