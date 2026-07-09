package com.aicabinet.device.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupValidator.class);
    private static final String DEV_INTERNAL_KEY = "dev-internal-key-change-me";

    private final Environment environment;
    private final InternalApiProperties internalApiProperties;
    private final MqttProperties mqttProperties;

    public ProductionStartupValidator(Environment environment,
                                      InternalApiProperties internalApiProperties,
                                      MqttProperties mqttProperties) {
        this.environment = environment;
        this.internalApiProperties = internalApiProperties;
        this.mqttProperties = mqttProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateProductionConfig() {
        if (!isStrictProfile()) {
            return;
        }
        requireSecret(internalApiProperties.key(), DEV_INTERNAL_KEY, "INTERNAL_API_KEY");
        if (isStagingProfile()) {
            log.warn("device-service staging mode — MQTT TLS check skipped");
            return;
        }
        if (mqttProperties.broker() != null && mqttProperties.broker().startsWith("tcp://")) {
            throw new IllegalStateException("Production MQTT must use ssl:// broker URL");
        }
        if (!mqttProperties.hasCredentials()) {
            throw new IllegalStateException("Production requires MQTT username/password");
        }
        if ("device-service".equals(mqttProperties.clientId())) {
            throw new IllegalStateException("Production requires a unique MQTT_CLIENT_ID per deployment");
        }
        if (mqttProperties.password().length() < 16) {
            throw new IllegalStateException("MQTT_PASSWORD must be at least 16 characters in production");
        }
        log.info("device-service production configuration validated");
    }

    private boolean isStrictProfile() {
        return isProdProfile() || isStagingProfile();
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStagingProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("staging".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return "true".equalsIgnoreCase(System.getenv("AICABINET_STAGING_MODE"));
    }

    private static void requireSecret(String actual, String forbiddenDefault, String name) {
        if (actual == null || actual.isBlank() || forbiddenDefault.equals(actual)) {
            throw new IllegalStateException("Production/staging requires a strong " + name);
        }
        if (actual.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters");
        }
    }
}
