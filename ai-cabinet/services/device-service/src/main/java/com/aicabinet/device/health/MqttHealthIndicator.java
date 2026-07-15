package com.aicabinet.device.health;

import com.aicabinet.device.mqtt.MqttConnectionRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class MqttHealthIndicator implements HealthIndicator {

    private final MqttConnectionRegistry registry;

    public MqttHealthIndicator(MqttConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (registry.isAllConnected()) {
            return Health.up()
                    .withDetail("publisher", registry.isPublisherConnected())
                    .withDetail("listener", registry.isListenerConnected())
                    .build();
        }
        return Health.down()
                .withDetail("publisher", registry.isPublisherConnected())
                .withDetail("listener", registry.isListenerConnected())
                .build();
    }
}
