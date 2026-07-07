package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.config.MqttProperties;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class MqttEventListener implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttEventListener.class);

    private final ObjectMapper objectMapper;
    private final TradeServiceClient tradeServiceClient;
    private final MqttProperties mqttProperties;
    private final MqttConnectOptionsFactory connectOptionsFactory;
    private final MqttConnectionRegistry connectionRegistry;
    private final DeviceMqttMetrics metrics;
    private final DoorEventDeduplicator deduplicator;
    private final String clientId;

    private MqttClient client;

    public MqttEventListener(
            ObjectMapper objectMapper,
            TradeServiceClient tradeServiceClient,
            MqttProperties mqttProperties,
            MqttConnectOptionsFactory connectOptionsFactory,
            MqttConnectionRegistry connectionRegistry,
            DeviceMqttMetrics metrics,
            DoorEventDeduplicator deduplicator) {
        this.objectMapper = objectMapper;
        this.tradeServiceClient = tradeServiceClient;
        this.mqttProperties = mqttProperties;
        this.connectOptionsFactory = connectOptionsFactory;
        this.connectionRegistry = connectionRegistry;
        this.metrics = metrics;
        this.deduplicator = deduplicator;
        this.clientId = mqttProperties.clientId() + "-evt-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    public void connect() throws MqttException {
        client = new MqttClient(mqttProperties.broker(), clientId, new MemoryPersistence());
        client.setCallback(this);
        client.connect(connectOptionsFactory.create());
        client.subscribe(MqttTopics.ALL_EVENTS, 1);
        connectionRegistry.setListenerConnected(true);
        log.info("MQTT event listener connected, subscribed {}", MqttTopics.ALL_EVENTS);
    }

    @PreDestroy
    public void disconnect() throws MqttException {
        connectionRegistry.setListenerConnected(false);
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        connectionRegistry.setListenerConnected(false);
        log.warn("MQTT event connection lost", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        metrics.recordMessageIn();
        String body = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            JsonNode node = objectMapper.readTree(body);
            String type = node.path("type").asText("");
            if (CabinetConstants.MQTT_EVENT_TYPE_DOOR.equals(type)) {
                handleDoorEvent(topic, node);
            } else if (CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT.equals(type)) {
                handleHeartbeat(topic, node);
            } else if ("ACK".equals(type)) {
                handleAck(node);
            }
        } catch (Exception e) {
            log.error("failed to handle mqtt message topic={} body={}", topic, body, e);
        }
    }

    private void handleAck(JsonNode node) {
        metrics.recordAck();
        log.debug("device ACK commandId={} success={}",
                node.path("commandId").asText(""), node.path("success").asBoolean(false));
    }

    private void handleHeartbeat(String topic, JsonNode node) {
        String deviceId = extractDeviceId(topic);
        if (node.has("deviceId")) {
            deviceId = node.path("deviceId").asText(deviceId);
        }
        String appVersion = textOrNull(node, "appVersion");
        if (appVersion == null) {
            appVersion = textOrNull(node, "app_version");
        }
        String firmwareVersion = textOrNull(node, "firmwareVersion");
        if (firmwareVersion == null) {
            firmwareVersion = textOrNull(node, "firmware_version");
        }
        forwardHeartbeat(deviceId, appVersion, firmwareVersion);
    }

    /** 心跳仅用于运营后台在线状态，trade 短暂不可达时不影响开门购物流程。 */
    private void forwardHeartbeat(String deviceId, String appVersion, String firmwareVersion) {
        try {
            tradeServiceClient.notifyHeartbeat(deviceId, appVersion, firmwareVersion);
            metrics.recordHeartbeatForwarded();
        } catch (Exception e) {
            metrics.recordHeartbeatDropped();
            log.warn("heartbeat forward skipped device={} (trade-service unreachable?): {}",
                    deviceId, e.getMessage());
        }
    }

    private void handleDoorEvent(String topic, JsonNode node) {
        String deviceId = extractDeviceId(topic);
        String sessionId = node.path("sessionId").asText(null);
        String doorStateStr = node.path("doorState").asText(null);
        if (sessionId == null || doorStateStr == null) {
            log.warn("invalid door event: {}", node);
            return;
        }
        if (deduplicator.isDuplicate(sessionId, doorStateStr)) {
            metrics.recordDoorDeduped();
            log.info("duplicate door event ignored session={} state={}", sessionId, doorStateStr);
            return;
        }
        DoorState doorState = DoorState.valueOf(doorStateStr);
        String videoUri = textOrNull(node, "videoUri");
        String uploadStatus = textOrNull(node, "uploadStatus");
        String videoClipsJson = textOrNull(node, "videoClipsJson");
        if (videoClipsJson == null) {
            videoClipsJson = textOrNull(node, "video_clips");
        }
        String cameraFusionMode = textOrNull(node, "cameraFusionMode");
        if (cameraFusionMode == null) {
            cameraFusionMode = textOrNull(node, "camera_fusion_mode");
        }
        try {
            tradeServiceClient.notifyDoorEvent(
                    sessionId, deviceId, doorState, videoUri, uploadStatus, videoClipsJson, cameraFusionMode);
            metrics.recordDoorForwarded();
        } catch (Exception e) {
            metrics.recordTradeFailure();
            throw e;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value != null && !value.isBlank() ? value : null;
    }

    private String extractDeviceId(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}
