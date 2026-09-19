package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.config.MqttProperties;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import com.aicabinet.device.service.DeviceCommandTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MqttEventListener implements MqttCallbackExtended {
    private static final String CURRENT_TEMP_C = "current_temp_c";
    private static final String CURRENTTEMPC = "currentTempC";
    // O4（2026-09-19）：原先这里各有一份 `EVENT_TYPE_ACK = "ACK"` / `EVENT_TYPE_ALERT = "ALERT"`
    // 局部副本，与 CabinetConstants.MQTT_EVENT_TYPE_* 是**同一事实的两处定义** —— 改一处忘另一处
    // 就是静默漂移。统一收敛到 CabinetConstants（本类其余分支早已用它）。
    /** C11：同一消息连续处理失败达到该次数后 ACK 丢弃（防重投风暴）。 */
    private static final int MAX_DELIVERY_FAILURES = 3;


    private static final Logger log = LoggerFactory.getLogger(MqttEventListener.class);

    private final ObjectMapper objectMapper;
    private final TradeServiceClient tradeServiceClient;
    private final MqttProperties mqttProperties;
    private final MqttConnectOptionsFactory connectOptionsFactory;
    private final MqttConnectionRegistry connectionRegistry;
    private final DeviceMqttMetrics metrics;
    private final DoorEventDeduplicator deduplicator;
    private final DeviceCommandTracker commandTracker;
    private final String clientId;

    /** C11：手动 ACK 模式下记录各消息连续处理失败次数（key = topic#messageId，重投之间保留）。 */
    private final ConcurrentHashMap<String, Integer> deliveryFailures = new ConcurrentHashMap<>();

    private MqttClient client;

    public MqttEventListener(
            ObjectMapper objectMapper,
            TradeServiceClient tradeServiceClient,
            MqttProperties mqttProperties,
            MqttConnectOptionsFactory connectOptionsFactory,
            MqttConnectionRegistry connectionRegistry,
            DeviceMqttMetrics metrics,
            DoorEventDeduplicator deduplicator,
            DeviceCommandTracker commandTracker) {
        this.objectMapper = objectMapper;
        this.tradeServiceClient = tradeServiceClient;
        this.mqttProperties = mqttProperties;
        this.connectOptionsFactory = connectOptionsFactory;
        this.connectionRegistry = connectionRegistry;
        this.metrics = metrics;
        this.deduplicator = deduplicator;
        this.commandTracker = commandTracker;
        this.clientId = mqttProperties.clientId() + "-evt-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    public void connect() throws MqttException {
        client = new MqttClient(mqttProperties.broker(), clientId, filePersistence("listener"));
        // C11：手动 ACK——处理成功/忽略/去重命中后显式 messageArrivedComplete，
        // 处理失败不 ACK，broker 重连后重投，避免 QoS1 消息被自动 PUBACK 而永久丢失。
        client.setManualAcks(true);
        client.setCallback(this);
        client.connect(connectOptionsFactory.create());
        subscribeEvents();
        connectionRegistry.setListenerConnected(true);
        log.info("MQTT event listener connected, subscribed {}", MqttTopics.ALL_EVENTS_SHARED);
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
    public void connectComplete(boolean reconnect, String serverURI) {
        connectionRegistry.setListenerConnected(true);
        try {
            subscribeEvents();
            log.info("MQTT event listener {}connected to {}, subscribed {}",
                    reconnect ? "re" : "", serverURI, MqttTopics.ALL_EVENTS_SHARED);
        } catch (MqttException e) {
            connectionRegistry.setListenerConnected(false);
            log.error("failed to resubscribe MQTT events after reconnect", e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        metrics.recordMessageIn();
        String body = new String(message.getPayload(), StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            // C11：报文不可解析，重投也无法恢复，ACK 丢弃
            log.warn("unparseable mqtt message topic={} dropped: {}", topic, e.toString());
            acknowledgeMessage(topic, message);
            return;
        }
        try {
            String type = node.path("type").asText("");
            if (CabinetConstants.MQTT_EVENT_TYPE_DOOR.equals(type)) {
                handleDoorEvent(topic, node, message);
            } else if (CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT.equals(type)) {
                handleHeartbeat(topic, node);
                acknowledgeMessage(topic, message);
            } else if (CabinetConstants.MQTT_EVENT_TYPE_ACK.equals(type)) {
                handleAck(topic, node);
                acknowledgeMessage(topic, message);
            } else if (CabinetConstants.MQTT_EVENT_TYPE_ALERT.equals(type)) {
                handleAlert(topic, node);
                acknowledgeMessage(topic, message);
            } else {
                log.debug("ignored mqtt message topic={} type={}", topic, type);
                acknowledgeMessage(topic, message);
            }
        } catch (Exception e) {
            onProcessingFailure(topic, body, message, e);
        }
    }

    /**
     * C11：手动 ACK 成功出口。处理成功 / 忽略 / 去重命中后必须调用，
     * 否则 broker 会在重连后重投该消息。
     */
    void acknowledgeMessage(String topic, MqttMessage message) {
        deliveryFailures.remove(failureKey(topic, message));
        if (client == null) {
            return;
        }
        try {
            client.messageArrivedComplete(message.getId(), message.getQos());
        } catch (MqttException e) {
            log.warn("manual ack failed topic={} messageId={}: {}", topic, message.getId(), e.toString());
        }
    }

    /**
     * C11：处理失败不 ACK（broker 重连后重投）；同一消息连续失败达阈值则 ACK 丢弃并记 error。
     */
    private void onProcessingFailure(String topic, String body, MqttMessage message, Exception e) {
        String key = failureKey(topic, message);
        int failures = deliveryFailures.merge(key, 1, Integer::sum);
        if (failures >= MAX_DELIVERY_FAILURES) {
            deliveryFailures.remove(key);
            log.error("mqtt message processing failed {} times, ack-drop topic={} messageId={} body={}",
                    failures, topic, message.getId(), body, e);
            acknowledgeMessage(topic, message);
            return;
        }
        log.error("failed to handle mqtt message topic={} messageId={} body={} failures={}/{} (left unacked for redelivery)",
                topic, message.getId(), body, failures, MAX_DELIVERY_FAILURES, e);
    }

    private static String failureKey(String topic, MqttMessage message) {
        return topic + "#" + message.getId();
    }

    /** H55：ACK 携带 topic，从 topic 解析 deviceId 与命令登记的 deviceId 比对，伪造来源被拒。 */
    private void handleAck(String topic, JsonNode node) {
        metrics.recordAck();
        String commandId = node.path("commandId").asText("");
        if (!commandId.isBlank()) {
            commandTracker.recordAck(commandId, node.path("success").asBoolean(false),
                    extractDeviceId(topic));
        }
        log.debug("device ACK commandId={} success={}",
                commandId, node.path("success").asBoolean(false));
    }

    /** H62a：edge 告警事件（如 EDGE_QUEUE_ABANDON）转发到运营告警通道（经 trade 内部端点）。 */
    private void handleAlert(String topic, JsonNode node) {
        String alertType = node.path("alertType").asText("");
        String message = node.path("message").asText("");
        String deviceId = extractDeviceId(topic);
        if (node.has("deviceId")) {
            deviceId = node.path("deviceId").asText(deviceId);
        }
        log.warn("edge alert received device={} alertType={} message={}", deviceId, alertType, message);
        tradeServiceClient.notifyOpsAlert(alertType, message, deviceId);
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
        forwardHeartbeat(deviceId, appVersion, firmwareVersion, parseTemp(node),
                parseDouble(node, "humidityPct", "humidity_pct"),
                parseDouble(node, "voltageV", "voltage_v"),
                parseDouble(node, "powerW", "power_w"));
    }

    private static Integer parseTemp(JsonNode node) {
        if (node.has(CURRENTTEMPC) && !node.get(CURRENTTEMPC).isNull()) {
            return node.path(CURRENTTEMPC).asInt();
        }
        if (node.has(CURRENT_TEMP_C) && !node.get(CURRENT_TEMP_C).isNull()) {
            return node.path(CURRENT_TEMP_C).asInt();
        }
        return null;
    }

    private static Double parseDouble(JsonNode node, String camel, String snake) {
        if (node.has(camel) && !node.get(camel).isNull()) {
            return node.path(camel).asDouble();
        }
        if (node.has(snake) && !node.get(snake).isNull()) {
            return node.path(snake).asDouble();
        }
        return null;
    }

    /** 心跳仅用于运营后台在线状态，trade 短暂不可达时不影响开门购物流程。 */
    private void forwardHeartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC,
                                  Double humidityPct, Double voltageV, Double powerW) {
        try {
            tradeServiceClient.notifyHeartbeat(deviceId, appVersion, firmwareVersion, currentTempC,
                    humidityPct, voltageV, powerW);
            metrics.recordHeartbeatForwarded();
        } catch (Exception e) {
            metrics.recordHeartbeatDropped();
            log.warn("heartbeat forward skipped device={} (trade-service unreachable?): {}",
                    deviceId, e.getMessage());
        }
    }

    private void handleDoorEvent(String topic, JsonNode node, MqttMessage message) {
        String topicDeviceId = extractDeviceId(topic);
        String bodyDeviceId = textOrNull(node, "deviceId");
        String deviceId = topicDeviceId;
        if (bodyDeviceId != null && !bodyDeviceId.isBlank()) {
            if (!"unknown".equals(topicDeviceId) && !bodyDeviceId.equals(topicDeviceId)) {
                log.warn("door event deviceId mismatch topic={} body={} session={}",
                        topicDeviceId, bodyDeviceId, node.path("sessionId").asText(null));
                acknowledgeMessage(topic, message);
                return;
            }
            deviceId = bodyDeviceId;
        }
        String sessionId = node.path("sessionId").asText(null);
        String doorStateStr = node.path("doorState").asText(null);
        if (sessionId == null || doorStateStr == null) {
            log.warn("invalid door event: {}", node);
            acknowledgeMessage(topic, message);
            return;
        }
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
        String gravityDeltasJson = textOrNull(node, "gravityDeltasJson");
        if (gravityDeltasJson == null) {
            gravityDeltasJson = textOrNull(node, "gravity_deltas");
        }
        // 稳定幂等键：session + 门状态 + 事件序号（有则用）；视频元数据易变不参与（B-2）
        String eventSeq = textOrNull(node, "eventSeq");
        if (eventSeq == null) {
            eventSeq = textOrNull(node, "event_seq");
        }
        String fingerprint = eventSeq != null
                ? "seq:" + eventSeq
                : String.join("|",
                nonNull(videoUri), nonNull(uploadStatus), nonNull(videoClipsJson),
                nonNull(cameraFusionMode), nonNull(gravityDeltasJson));
        if (deduplicator.seen(sessionId, doorStateStr, fingerprint)) {
            metrics.recordDoorDeduped();
            log.info("duplicate door event ignored session={} state={}", sessionId, doorStateStr);
            acknowledgeMessage(topic, message);
            return;
        }
        DoorState doorState;
        try {
            doorState = DoorState.valueOf(doorStateStr);
        } catch (IllegalArgumentException e) {
            // M11：seen() 不写键，无需 clear
            log.warn("invalid doorState={} session={} topic={}", doorStateStr, sessionId, topic);
            acknowledgeMessage(topic, message);
            return;
        }
        try {
            tradeServiceClient.notifyDoorEvent(new com.aicabinet.common.dto.DoorEventRequest(
                    sessionId, deviceId, doorState, System.currentTimeMillis(),
                    videoUri, uploadStatus, videoClipsJson, cameraFusionMode, gravityDeltasJson));
            // M11：转发成功后才写幂等键；失败不 mark，重投后 seen=false 可再处理
            deduplicator.mark(sessionId, doorStateStr, fingerprint);
            metrics.recordDoorForwarded();
            acknowledgeMessage(topic, message);
        } catch (Exception e) {
            // C11/M11：转发失败不 ACK 也不 mark，broker 重连后重投（B-2）
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

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private String extractDeviceId(String topic) {
        String t = topic == null ? "" : topic.trim();
        // 共享订阅投递偶发带 $share/{group}/ 前缀（B-4）
        if (t.startsWith("$share/")) {
            String[] shareParts = t.split("/", 3);
            t = shareParts.length >= 3 ? shareParts[2] : t;
        }
        String[] parts = t.split("/");
        if (parts.length >= 2 && "cabinet".equals(parts[0])) {
            return parts[1];
        }
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }

    private void subscribeEvents() throws MqttException {
        if (client != null && client.isConnected()) {
            // Shared subscription: only one instance receives each event with replicas
            client.subscribe(MqttTopics.ALL_EVENTS_SHARED, 1);
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
        } catch (Exception e) {
            throw new IllegalStateException("failed to create MQTT persistence dir " + dir, e);
        }
        return new MqttDefaultFilePersistence(dir.toString());
    }
}
