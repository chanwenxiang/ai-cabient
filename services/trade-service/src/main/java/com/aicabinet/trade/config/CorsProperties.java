package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "aicabinet.cors")
public record CorsProperties(List<String> allowedOrigins) {}
