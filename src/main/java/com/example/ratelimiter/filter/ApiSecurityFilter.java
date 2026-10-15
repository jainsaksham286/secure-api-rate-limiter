package com.example.ratelimiter.filter;

import com.example.ratelimiter.config.SecurityProperties;
import com.example.ratelimiter.model.BucketDecision;
import com.example.ratelimiter.service.RateLimiterService;
import com.example.ratelimiter.service.StatsService;
import com.example.ratelimiter.util.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
/**
 * Authenticates demo/admin requests and applies the configured per-client token
 * bucket.
 */
public class ApiSecurityFilter extends OncePerRequestFilter {
    public static final String CLIENT_ATTRIBUTE = ApiSecurityFilter.class.getName() + ".client";
    public static final String DECISION_ATTRIBUTE = ApiSecurityFilter.class.getName() + ".decision";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityFilter.class);
    private static final String AUTH_MESSAGE = "Authentication failed";

    private final RateLimiterService rateLimiterService;
    private final StatsService statsService;
    private final SecurityProperties securityProperties;
    private final ApiErrorWriter errorWriter;

    public ApiSecurityFilter(
            RateLimiterService rateLimiterService,
            StatsService statsService,
            SecurityProperties securityProperties,
            ApiErrorWriter errorWriter) {
        this.rateLimiterService = rateLimiterService;
        this.statsService = statsService;
        this.securityProperties = securityProperties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return request.getMethod().equals("OPTIONS")
                || (!path.equals("/api/demo/data") && !path.startsWith("/api/monitoring/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/monitoring/")) {
            if (!matchesSecret(securityProperties.getAdminKey(), request.getHeader("X-Admin-Key"))) {
                errorWriter.write(request, response, 401, "Unauthorized", AUTH_MESSAGE);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            statsService.recordRejected(null, "missing_key");
            errorWriter.write(request, response, 401, "Unauthorized", AUTH_MESSAGE);
            return;
        }
        RateLimiterService.ClientBucket client = rateLimiterService.findClient(apiKey);
        if (client == null) {
            statsService.recordRejected(null, "invalid_key");
            errorWriter.write(request, response, 401, "Unauthorized", AUTH_MESSAGE);
            return;
        }

        BucketDecision decision = client.tryConsume();
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.capacity()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remainingTokens()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetAfterSeconds()));
        request.setAttribute(CLIENT_ATTRIBUTE, client);
        request.setAttribute(DECISION_ATTRIBUTE, decision);
        if (!decision.accepted()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            statsService.recordRejected(client.clientId(), "rate_limited");
            LOGGER.info("Rate limit rejected request for clientId={}", client.clientId());
            errorWriter.write(request, response, 429, "Too Many Requests", "Rate limit exceeded");
            return;
        }
        statsService.recordAccepted(client.clientId());
        chain.doFilter(request, response);
    }

    private static boolean matchesSecret(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        try {
            byte[] expectedDigest = MessageDigest.getInstance("SHA-256")
                    .digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] suppliedDigest = MessageDigest.getInstance("SHA-256")
                    .digest(supplied.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedDigest, suppliedDigest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}