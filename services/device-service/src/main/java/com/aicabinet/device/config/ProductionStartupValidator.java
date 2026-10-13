package com.aicabinet.device.config;

import com.aicabinet.common.security.InternalApiProperties;
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
        if (isProdProfile() && !internalApiProperties.hasCidrRestriction()) {
            throw new IllegalStateException(
                    "Production requires INTERNAL_API_ALLOWED_CIDRS for device-service /internal/**");
        }
        if (isStagingProfile() && !internalApiProperties.hasCidrRestriction()) {
            throw new IllegalStateException(
                    "Staging requires INTERNAL_API_ALLOWED_CIDRS for device-service /internal/**");
        }
        if (isStagingProfile()) {
            log.warn("device-service staging mode — MQTT TLS check skipped");
            return;
        }
        if (mqttProperties.broker() != null && mqttProperties.broker().startsWith("tcp://")) {
            // 2026-10-13 生产首启调整：P2-10 的 TLS truststore 供给链未落地，且当前无任何柜机
            // 经 MQTT 接入（将邑柜走 WS 网关），设备接入前必须补齐 TLS 并删除本豁免。
            // 显式豁免开关默认缺省=仍硬拒绝，fail-loud 不变。
            if (!"true".equalsIgnoreCase(environment.getProperty("MQTT_ALLOW_TCP", ""))) {
                throw new IllegalStateException("Production MQTT must use ssl:// broker URL "
                        + "(or set MQTT_ALLOW_TCP=true explicitly for the pre-device TLS transition)");
            }
            log.warn("MQTT_ALLOW_TCP=true —— 生产 MQTT 暂走明文 tcp://（compose 内网、当前无柜机经 MQTT 接入）；"
                    + "设备接入前必须落地 TLS truststore 供给链（审计 P2-10）并删除本豁免");
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
