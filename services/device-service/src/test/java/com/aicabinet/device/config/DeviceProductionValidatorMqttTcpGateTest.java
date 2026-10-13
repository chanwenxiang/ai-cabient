package com.aicabinet.device.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.common.security.InternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * 钉住 device 生产门禁的 MQTT TLS 豁免开关（2026-10-13 生产首启调整）：
 * tcp:// 默认仍硬拒绝；显式 MQTT_ALLOW_TCP=true 才放行（P2-10 TLS 落地前的过渡，fail-loud 不变）。
 */
class DeviceProductionValidatorMqttTcpGateTest {

    private ProductionStartupValidator build(String brokerUrl, String allowTcp) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(environment.getProperty("MQTT_ALLOW_TCP", "")).thenReturn(allowTcp);

        InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
        when(internalApiProperties.key()).thenReturn("prod-internal-key-32bytes-at-least!!");
        when(internalApiProperties.hasCidrRestriction()).thenReturn(true);

        MqttProperties mqttProperties = mock(MqttProperties.class);
        when(mqttProperties.broker()).thenReturn(brokerUrl);
        when(mqttProperties.hasCredentials()).thenReturn(true);
        when(mqttProperties.clientId()).thenReturn("device-service-prod-120.25.246.179");
        when(mqttProperties.password()).thenReturn("prod-mqtt-password-32bytes-min!!");

        return new ProductionStartupValidator(environment, internalApiProperties, mqttProperties);
    }

    @Test
    void prodTcpBrokerStillRejectedByDefault() {
        ProductionStartupValidator v = build("tcp://emqx:1883", "");
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ssl://");
    }

    @Test
    void prodTcpBrokerAllowedWithExplicitOptOut() {
        ProductionStartupValidator v = build("tcp://emqx:1883", "true");
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }

    @Test
    void prodSslBrokerNeverRequiresOptOut() {
        ProductionStartupValidator v = build("ssl://emqx:8883", "");
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }
}
