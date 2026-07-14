package com.aicabinet.trade.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Resilience4j 熔断与重试配置。
 * vision-service 调用: 熔断保护，上游不可用时快速失败
 * device-service 调用: 重试保护，网络抖动时自动重试
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

    @Bean
    public CircuitBreakerConfig visionServiceCircuitBreakerConfig() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(RestClientException.class, IllegalStateException.class)
                .build();
        log.info("Resilience4j circuit breaker configured: vision-service, window=10 threshold=50%");
        return config;
    }

    @Bean
    public RetryConfig deviceServiceRetryConfig() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(RestClientException.class)
                .build();
        log.info("Resilience4j retry configured: device-service, max=3 wait=500ms");
        return config;
    }

    @Bean
    public io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry(
            CircuitBreakerConfig visionCfg) {
        return io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.of(
                java.util.Map.of("visionService", visionCfg)
        );
    }

    @Bean
    public io.github.resilience4j.retry.RetryRegistry retryRegistry(RetryConfig deviceCfg) {
        return io.github.resilience4j.retry.RetryRegistry.of(
                java.util.Map.of("deviceService", deviceCfg)
        );
    }
}
