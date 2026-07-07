package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.config.MqttProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;

@Component
public class MqttCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttCommandPublisher.class);

    private final ObjectMapper objectMapper;
    private final MqttProperties mqttProperties;
    private final MqttConnectOptionsFactory connectOptionsFactory;
    private final MqttConnectionRegistry connectionRegistry;
    private final String clientId;

    private MqttClient client;

    public MqttCommandPublisher(
            ObjectMapper objectMapper,
            MqttProperties mqttProperties,
            MqttConnectOptionsFactory connectOptionsFactory,
            MqttConnectionRegistry connectionRegistry) {
        this.objectMapper = objectMapper;
        this.mqttProperties = mqttProperties;
        this.connectOptionsFactory = connectOptionsFactory;
        this.connectionRegistry = connectionRegistry;
        this.clientId = mqttProperties.clientId() + "-pub-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    public void connect() throws MqttException {
        client = new MqttClient(mqttProperties.broker(), clientId, new MemoryPersistence());
        client.connect(connectOptionsFactory.create());
        connectionRegistry.setPublisherConnected(true);
        log.info("MQTT command publisher connected to {}", mqttProperties.broker());
    }

    @PreDestroy
    public void disconnect() throws MqttException {
        connectionRegistry.setPublisherConnected(false);
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }

    public void publishOpenDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
        try {
            String commandId = UUID.randomUUID().toString();
            Map<String, Object> payload = Map.of(
                    "commandId", commandId,
                    "type", CabinetConstants.MQTT_CMD_OPEN_DOOR,
                    "sessionId", sessionId,
                    "userId", userId,
                    "operatorMode", operatorMode,
                    "expireAt", System.currentTimeMillis() + 60_000
            );
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            MqttMessage message = new MqttMessage(bytes);
            message.setQos(1);
            client.publish(MqttTopics.command(deviceId), message);
            log.info("published OPEN_DOOR to {} commandId={}", deviceId, commandId);
        } catch (Exception e) {
            throw new RuntimeException("failed to publish open door command", e);
        }
    }
}
