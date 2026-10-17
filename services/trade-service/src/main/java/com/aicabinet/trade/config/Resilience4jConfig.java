package com.aicabinet.trade.config;

import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Resilience4j 熔断与重试配置。
 * device-service 调用: 重试保护，网络抖动时自动重试
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

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
    public io.github.resilience4j.retry.RetryRegistry retryRegistry(RetryConfig deviceCfg) {
        return io.github.resilience4j.retry.RetryRegistry.of(
                java.util.Map.of("deviceService", deviceCfg)
        );
    }
}
