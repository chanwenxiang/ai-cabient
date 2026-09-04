package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.config.MqttProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Component
public class MqttCommandPublisher {
    private static final String COMMANDID = "commandId";
    private static final String EXPIREAT = "expireAt";


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
        client = new MqttClient(mqttProperties.broker(), clientId, filePersistence("publisher"));
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

    public String publishOpenDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
        try {
            ensureConnected();
            String commandId = UUID.randomUUID().toString();
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put(COMMANDID, commandId);
            payload.put("type", CabinetConstants.MQTT_CMD_OPEN_DOOR);
            payload.put("sessionId", sessionId != null ? sessionId : "");
            payload.put("userId", userId != null ? userId : 0L);
            payload.put("operatorMode", operatorMode);
            payload.put(EXPIREAT, System.currentTimeMillis() + 60_000);
            publish(deviceId, payload);
            log.info("published OPEN_DOOR to {} commandId={}", deviceId, commandId);
            return commandId;
        } catch (MqttException | JsonProcessingException e) {
            throw new IllegalStateException("failed to publish open door command", e);
        }
    }

    public String publishSetTargetTemp(String deviceId, int targetTempC) {
        try {
            ensureConnected();
            String commandId = UUID.randomUUID().toString();
            Map<String, Object> payload = Map.of(
                    COMMANDID, commandId,
                    "type", CabinetConstants.MQTT_CMD_SET_TARGET_TEMP,
                    "targetTempC", targetTempC,
                    EXPIREAT, System.currentTimeMillis() + 60_000
            );
            publish(deviceId, payload);
            log.info("published SET_TARGET_TEMP to {} commandId={} target={}", deviceId, commandId, targetTempC);
            return commandId;
        } catch (MqttException | JsonProcessingException e) {
            throw new IllegalStateException("failed to publish set target temp command", e);
        }
    }

    /** 运维指令：LOCK / UNLOCK / REBOOT（与真设备同一 MQTT 通道，模拟器可 ACK） */
    public String publishOpsCommand(String deviceId, String commandType) {
        try {
            ensureConnected();
            String commandId = UUID.randomUUID().toString();
            Map<String, Object> payload = Map.of(
                    COMMANDID, commandId,
                    "type", commandType,
                    EXPIREAT, System.currentTimeMillis() + 60_000
            );
            publish(deviceId, payload);
            log.info("published {} to {} commandId={}", commandType, deviceId, commandId);
            return commandId;
        } catch (MqttException | JsonProcessingException e) {
            throw new IllegalStateException("failed to publish ops command " + commandType, e);
        }
    }

    private void publish(String deviceId, Map<String, Object> payload) throws MqttException, JsonProcessingException {
        ensureConnected();
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        MqttMessage message = new MqttMessage(bytes);
        message.setQos(1);
        client.publish(MqttTopics.command(deviceId), message);
    }

    private synchronized void ensureConnected() throws MqttException {
        if (client == null) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
        }
        if (!client.isConnected()) {
            connectionRegistry.setPublisherConnected(false);
            client.connect(connectOptionsFactory.create());
            connectionRegistry.setPublisherConnected(true);
        }
    }

    private MqttDefaultFilePersistence filePersistence(String name) {
        String root = mqttProperties.persistenceDir();
        if (root == null || root.isBlank()) {
            root = "data/mqtt-paho";
        }
        Path dir = Path.of(root, name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create MQTT persistence dir " + dir, e);
        }
        return new MqttDefaultFilePersistence(dir.toString());
    }
}
