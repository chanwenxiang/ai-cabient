package com.aicabinet.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.mqtt")
public record MqttProperties(
        String broker,
        String clientId,
        String username,
        String password,
        String trustStorePath,
        String trustStorePassword,
        String persistenceDir
) {
    public boolean isSsl() {
        return broker != null && broker.startsWith("ssl://");
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
