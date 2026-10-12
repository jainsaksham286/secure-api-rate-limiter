package com.example.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SecureApiRateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureApiRateLimiterApplication.class, args);
    }
}