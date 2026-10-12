package com.example.ratelimiter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimiter.util.TimeSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.boot.test.context.TestConfiguration;

@SpringBootTest(properties = {
        "app.rate-limit.clients.demo-free.capacity=2",
        "app.rate-limit.clients.demo-free.refill-tokens-per-second=1.0"
})
@AutoConfigureMockMvc
@Import(ApiIntegrationTest.TestClockConfiguration.class)
class ApiIntegrationTest {
    private static final String FREE_KEY = "demo-key-free-123";
    private static final String ADMIN_KEY = "fake-admin-key-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeTimeSource timeSource;

    @Test
    void apiSecurityRateLimitsMonitoringAndHealthWorkTogether() throws Exception {
        MvcResult publicResult = mockMvc.perform(get("/api/demo/public"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertNotSecret(publicResult.getResponse().getContentAsString());

        String missingBody = mockMvc.perform(get("/api/demo/data"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication failed"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String invalidBody = mockMvc.perform(get("/api/demo/data").header("X-API-Key", "attacker-controlled-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed"))
                .andReturn().getResponse().getContentAsString();
        assertNotSecret(missingBody);
        assertNotSecret(invalidBody);

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
        assertNotSecret(firstAccepted.getResponse().getContentAsString());
        assertNotSecret(secondAccepted.getResponse().getContentAsString());

        mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));

        mockMvc.perform(get("/api/monitoring/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed"));
        mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", "wrong-admin-key"))
                .andExpect(status().isUnauthorized());

        String initialSummary = mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(6))
                .andExpect(jsonPath("$.acceptedRequests").value(3))
                .andExpect(jsonPath("$.rejectedRequests").value(3))
                .andExpect(jsonPath("$.activeClients").value(2))
                .andExpect(jsonPath("$.unknownClients").value(1))
                .andExpect(jsonPath("$.rejectionReasons.missing_key").value(1))
                .andExpect(jsonPath("$.rejectionReasons.invalid_key").value(1))
                .andExpect(jsonPath("$.rejectionReasons.rate_limited").value(1))
                .andReturn().getResponse().getContentAsString();
        assertNotSecret(initialSummary);

        String clients = mockMvc.perform(get("/api/monitoring/clients").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value("demo-free"))
                .andExpect(jsonPath("$[0].total").value(3))
                .andExpect(jsonPath("$[0].rejected").value(1))
                .andReturn().getResponse().getContentAsString();
        assertNotSecret(clients);
        org.junit.jupiter.api.Assertions.assertFalse(clients.contains("apiKey"));

        String timeSeries = mockMvc.perform(get("/api/monitoring/timeseries").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketSeconds").value(5))
                .andExpect(jsonPath("$.points.length()").value(60))
                .andReturn().getResponse().getContentAsString();
        assertNotSecret(timeSeries);

        timeSource.advance(TimeUnit.SECONDS.toNanos(1));
        mockMvc.perform(get("/api/demo/data").header("X-API-Key", FREE_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        mockMvc.perform(get("/api/monitoring/summary").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(7))
                .andExpect(jsonPath("$.acceptedRequests").value(4))
                .andExpect(jsonPath("$.rejectedRequests").value(3));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static void assertNotSecret(String response) {
        org.junit.jupiter.api.Assertions.assertFalse(response.contains(FREE_KEY));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains(ADMIN_KEY));
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