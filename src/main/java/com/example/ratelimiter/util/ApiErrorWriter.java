package com.example.ratelimiter.util;

import com.example.ratelimiter.model.ApiErrorResponse;
import com.example.ratelimiter.filter.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String error,
            String message)
            throws IOException {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                Instant.now(), status, error, message, request.getRequestURI(), requestId));
    }
}