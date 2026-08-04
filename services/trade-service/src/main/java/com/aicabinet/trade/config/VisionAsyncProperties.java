package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.vision-async")
public record VisionAsyncProperties(boolean enabled) {}
