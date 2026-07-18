package com.aicabinet.simulator;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.common.storage.ObjectStorageKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 桌面设备模拟器：订阅 OPEN_DOOR，模拟开门→购物→关门；支持断网续传、多摄、OTA 心跳。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code AICABINET_SIM_VIDEO_FILE} — 顶摄测试图，上传 MinIO/OSS</li>
 *   <li>{@code AICABINET_SIM_SIDE_VIDEO_FILE} — 侧摄（多摄模式）</li>
 *   <li>{@code AICABINET_SIM_OFFLINE_UPLOAD=true} — 关门先 LOCAL_QUEUED，延迟后再上传</li>
 *   <li>{@code AICABINET_SIM_MULTI_CAMERA=true} — 顶摄+侧摄融合</li>
 *   <li>{@code AICABINET_SIM_APP_VERSION=0.9.0} — OTA 检查用版本号</li>
 * </ul>
 */
public class DeviceSimulator implements MqttCallback {

    private static final String DEFAULT_BUCKET = "cabinet-videos";

    private final String deviceId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private MqttClient client;
    private boolean lastOperatorMode;
    private long lastUserId;

    public DeviceSimulator(String deviceId) {
        this.deviceId = deviceId;
    }

    public void start(String broker) throws MqttException {
        client = new MqttClient(broker, "sim-" + deviceId + "-" + UUID.randomUUID().toString().substring(0, 6),
                new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        client.setCallback(this);
        client.connect(options);
        client.subscribe(MqttTopics.command(deviceId), 1);
        System.out.println("[simulator] listening on " + MqttTopics.command(deviceId));
        logVideoConfig();
        checkOta();
        publishHeartbeat();
        startHeartbeatLoop();
    }

    private void logVideoConfig() {
        System.out.println("[simulator] offline=" + offlineUploadEnabled()
                + " multiCamera=" + multiCameraEnabled()
                + " appVersion=" + appVersion());
    }

    private void checkOta() {
        String tradeUrl = env("TRADE_SERVICE_URL", "http://localhost:8080");
        String apiKey = env("INTERNAL_API_KEY", "dev-internal-key-change-me");
        String url = tradeUrl + "/internal/v1/devices/" + deviceId
                + "/ota/check?currentVersion=" + appVersion() + "&channel=stable";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Internal-Api-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode root = mapper.readTree(res.body());
                JsonNode data = root.path("data");
                if (data.path("updateAvailable").asBoolean(false)) {
                    System.out.println("[simulator] OTA available -> " + data.path("targetVersion").asText()
                            + " mandatory=" + data.path("mandatory").asBoolean());
                } else {
                    System.out.println("[simulator] OTA up to date");
                }
            }
        } catch (Exception e) {
            System.out.println("[simulator] OTA check skipped: " + e.getMessage());
        }
    }

    private void startHeartbeatLoop() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000);
                    publishHeartbeat();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[simulator] heartbeat failed: " + e.getMessage());
                }
            }
        }, "sim-heartbeat-" + deviceId);
        t.setDaemon(true);
        t.start();
    }

    private void publishHeartbeat() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT);
            payload.put("deviceId", deviceId);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("appVersion", appVersion());
            payload.put("firmwareVersion", env("AICABINET_SIM_FIRMWARE_VERSION", "1.0.0"));
            client.publish(MqttTopics.event(deviceId), new MqttMessage(mapper.writeValueAsBytes(payload)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("[simulator] connection lost: " + cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText();
        String commandId = node.path("commandId").asText();
        if (!CabinetConstants.MQTT_CMD_OPEN_DOOR.equals(type)) {
            if (CabinetConstants.MQTT_CMD_SET_TARGET_TEMP.equals(type)
                    || CabinetConstants.MQTT_CMD_LOCK.equals(type)
                    || CabinetConstants.MQTT_CMD_UNLOCK.equals(type)
                    || CabinetConstants.MQTT_CMD_REBOOT.equals(type)) {
                System.out.println("[simulator] " + type + " received");
                publishAck(commandId);
            }
            return;
        }

        String sessionId = node.path("sessionId").asText();
        long userId = node.path("userId").asLong(0L);
        boolean operatorMode = node.path("operatorMode").asBoolean(false);
        System.out.println("[simulator] OPEN_DOOR received session=" + sessionId
                + " userId=" + userId + " operatorMode=" + operatorMode);

        publishAck(commandId);
        scheduleShoppingFlow(sessionId, userId, operatorMode);
    }

    /** 购物/关门在独立线程执行，避免阻塞 Paho 回调导致 keepalive 超时断连。 */
    private void scheduleShoppingFlow(String sessionId, long userId, boolean operatorMode) {
        Thread t = new Thread(() -> {
            try {
                lastUserId = userId;
                lastOperatorMode = operatorMode;
                Thread.sleep(500);
                publishDoorEvent(sessionId, DoorState.OPEN, null, null, null, null, null);
                long shoppingMs = shoppingDelayMs();
                System.out.println("[simulator] door OPEN, shopping " + (shoppingMs / 1000) + "s...");
                Thread.sleep(shoppingMs);
                completeDoorClose(sessionId);
            } catch (Exception e) {
                System.err.println("[simulator] shopping flow failed session=" + sessionId + ": " + e.getMessage());
            }
        }, "sim-shop-" + sessionId);
        t.setDaemon(true);
        t.start();
    }

    private void completeDoorClose(String sessionId) throws Exception {
        if (offlineUploadEnabled()) {
            publishDoorEvent(sessionId, DoorState.CLOSED, null, "LOCAL_QUEUED", null, null, resolveGravityJson());
            System.out.println("[simulator] door CLOSED offline, queued local upload");
            scheduleDeferredUpload(sessionId);
            return;
        }

        if (multiCameraEnabled()) {
            VideoPayload payload = resolveMultiCameraPayload(sessionId);
            publishDoorEvent(sessionId, DoorState.CLOSED, payload.primaryUri(), "UPLOADED",
                    payload.clipsJson(), "MULTI", resolveGravityJson());
            System.out.println("[simulator] door CLOSED multi-camera primary=" + payload.primaryUri());
            return;
        }

        String videoUri = resolveVideoUri(sessionId, "top");
        publishDoorEvent(sessionId, DoorState.CLOSED, videoUri, "UPLOADED", null, "SINGLE", resolveGravityJson());
        System.out.println("[simulator] door CLOSED video=" + videoUri);
    }

    private void scheduleDeferredUpload(String sessionId) {
        long delay = Long.parseLong(env("AICABINET_SIM_OFFLINE_DELAY_MS", "5000"));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay);
                VideoPayload payload = multiCameraEnabled()
                        ? resolveMultiCameraPayload(sessionId)
                        : new VideoPayload(resolveVideoUri(sessionId, "top"), null, "SINGLE");
                postAttachVideo(sessionId, payload);
                System.out.println("[simulator] offline upload completed session=" + sessionId);
            } catch (Exception e) {
                System.err.println("[simulator] deferred upload failed: " + e.getMessage());
            }
        }, "sim-upload-" + sessionId);
        t.setDaemon(true);
        t.start();
    }

    private void postAttachVideo(String sessionId, VideoPayload payload) throws Exception {
        String tradeUrl = env("TRADE_SERVICE_URL", "http://localhost:8080");
        String apiKey = env("INTERNAL_API_KEY", "dev-internal-key-change-me");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("deviceId", deviceId);
        body.put("videoUri", payload.primaryUri());
        body.put("uploadStatus", "UPLOADED");
        if (payload.clipsJson() != null) {
            body.put("videoClipsJson", payload.clipsJson());
            body.put("cameraFusionMode", payload.fusionMode());
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tradeUrl + "/internal/v1/sessions/video"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("attach video HTTP " + res.statusCode() + " " + res.body());
        }
    }

    private VideoPayload resolveMultiCameraPayload(String sessionId) throws Exception {
        String topFile = env("AICABINET_SIM_VIDEO_FILE", null);
        String sideFile = env("AICABINET_SIM_SIDE_VIDEO_FILE", topFile);
        if (topFile == null || topFile.isBlank()) {
            String topUri = resolveVideoUri(sessionId, "top");
            String sideUri = resolveVideoUri(sessionId, "side");
            return new VideoPayload(topUri, clipsJson(topUri, sideUri), "MULTI");
        }
        String topUri = uploadViaPresign(Path.of(topFile.trim()), sessionId, lastUserId, "top");
        String sideUri = sideFile != null && !sideFile.isBlank()
                ? uploadViaPresign(Path.of(sideFile.trim()), sessionId, lastUserId, "side")
                : topUri;
        return new VideoPayload(topUri, clipsJson(topUri, sideUri), "MULTI");
    }

    private String clipsJson(String topUri, String sideUri) throws Exception {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> clips = new ArrayList<>();
        clips.add(Map.of("camera", "TOP", "videoUri", topUri, "capturedAt", now));
        clips.add(Map.of("camera", "SIDE", "videoUri", sideUri, "capturedAt", now));
        return mapper.writeValueAsString(clips);
    }

    private String resolveVideoUri(String sessionId, String camera) {
        String configured = System.getenv("AICABINET_SIM_VIDEO_URI");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }

        String localFile = System.getenv("AICABINET_SIM_VIDEO_FILE");
        if (localFile != null && !localFile.isBlank()) {
            try {
                return uploadViaPresign(Path.of(localFile.trim()), sessionId, lastUserId, camera);
            } catch (Exception e) {
                throw new RuntimeException("failed to upload sim video", e);
            }
        }

        String objectKey = ObjectStorageKeys.simMediaKey(deviceId, lastUserId, sessionId, camera, ".mp4");
        return storageScheme() + "://" + bucket() + "/" + objectKey;
    }

    private String uploadViaPresign(Path localPath, String sessionId, long userId, String camera) throws Exception {
        if (!Files.isRegularFile(localPath)) {
            throw new IllegalArgumentException("video file not found: " + localPath.toAbsolutePath());
        }

        String tradeUrl = env("TRADE_SERVICE_URL", "http://localhost:8080");
        String apiKey = env("INTERNAL_API_KEY", "dev-internal-key-change-me");
        String ext = extension(localPath);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("deviceId", deviceId);
        body.put("userId", userId);
        body.put("camera", camera);
        body.put("extension", ext);
        body.put("sim", true);

        HttpRequest presignReq = HttpRequest.newBuilder()
                .uri(URI.create(tradeUrl + "/internal/v1/sessions/video-upload-url"))
                .header("X-Internal-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> presignRes = http.send(presignReq, HttpResponse.BodyHandlers.ofString());
        if (presignRes.statusCode() != 200) {
            throw new IllegalStateException("presign HTTP " + presignRes.statusCode() + " " + presignRes.body());
        }
        JsonNode data = mapper.readTree(presignRes.body()).path("data");
        String uploadUrl = resolveUploadUrl(data.path("uploadUrl").asText());
        String videoUri = data.path("videoUri").asText();
        if (uploadUrl.isBlank() || videoUri.isBlank()) {
            throw new IllegalStateException("presign response missing uploadUrl/videoUri");
        }

        HttpRequest putReq = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Content-Type", contentType(ext))
                .timeout(Duration.ofSeconds(120))
                .PUT(HttpRequest.BodyPublishers.ofFile(localPath))
                .build();
        HttpResponse<Void> putRes = http.send(putReq, HttpResponse.BodyHandlers.discarding());
        if (putRes.statusCode() < 200 || putRes.statusCode() >= 300) {
            throw new IllegalStateException("presign upload HTTP " + putRes.statusCode());
        }

        System.out.println("[simulator] uploaded " + localPath + " -> " + videoUri);
        return videoUri;
    }

    /** 容器内上传走 MINIO_ENDPOINT，避免 presign 公网 URL（localhost）不可达。 */
    private String resolveUploadUrl(String presignedUrl) {
        String internalEndpoint = env("MINIO_ENDPOINT", null);
        if (internalEndpoint == null || internalEndpoint.isBlank()) {
            return presignedUrl;
        }
        try {
            URI internal = URI.create(internalEndpoint.endsWith("/")
                    ? internalEndpoint.substring(0, internalEndpoint.length() - 1)
                    : internalEndpoint);
            URI signed = URI.create(presignedUrl);
            int port = internal.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(internal.getScheme()) ? 443 : 80;
            }
            return new URI(
                    internal.getScheme(),
                    signed.getUserInfo(),
                    internal.getHost(),
                    port,
                    signed.getPath(),
                    signed.getQuery(),
                    signed.getFragment()
            ).toString();
        } catch (Exception e) {
            System.err.println("[simulator] rewrite upload url failed: " + e.getMessage());
            return presignedUrl;
        }
    }

    private void publishAck(String commandId) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "type", CabinetConstants.MQTT_EVENT_TYPE_ACK,
                "commandId", commandId,
                "success", true,
                "timestamp", System.currentTimeMillis()
        ));
        client.publish(MqttTopics.event(deviceId), new MqttMessage(payload));
    }

    private void publishDoorEvent(String sessionId,
                                  DoorState state,
                                  String videoUri,
                                  String uploadStatus,
                                  String videoClipsJson,
                                  String cameraFusionMode,
                                  String gravityDeltasJson) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", CabinetConstants.MQTT_EVENT_TYPE_DOOR);
        data.put("sessionId", sessionId);
        data.put("doorState", state.name());
        data.put("timestamp", System.currentTimeMillis());
        if (videoUri != null) data.put("videoUri", videoUri);
        if (uploadStatus != null) data.put("uploadStatus", uploadStatus);
        if (videoClipsJson != null) data.put("videoClipsJson", videoClipsJson);
        if (cameraFusionMode != null) data.put("cameraFusionMode", cameraFusionMode);
        if (gravityDeltasJson != null) data.put("gravityDeltasJson", gravityDeltasJson);

        MqttMessage msg = new MqttMessage(mapper.writeValueAsBytes(data));
        msg.setQos(1);
        client.publish(MqttTopics.event(deviceId), msg);
    }

    /** 关门重力：仅当显式配置时才注入，避免演示时未取货却被扣款。 */
    private String resolveGravityJson() {
        String configured = env("AICABINET_SIM_GRAVITY_JSON", null);
        if (configured != null) {
            return configured;
        }
        if (lastOperatorMode) {
            return null;
        }
        String sku = env("AICABINET_SIM_GRAVITY_SKU", null);
        if (sku == null || sku.isBlank()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(List.of(
                    Map.of("skuId", sku.trim(),
                            "delta", -1,
                            "slotId", env("AICABINET_SIM_GRAVITY_SLOT", "A1"))));
        } catch (Exception e) {
            return null;
        }
    }

    private static long shoppingDelayMs() {
        return Long.parseLong(env("AICABINET_SIM_SHOPPING_MS", "30000"));
    }

    private static boolean offlineUploadEnabled() {
        return "true".equalsIgnoreCase(env("AICABINET_SIM_OFFLINE_UPLOAD", "false"));
    }

    private static boolean multiCameraEnabled() {
        return "true".equalsIgnoreCase(env("AICABINET_SIM_MULTI_CAMERA", "false"));
    }

    private static String appVersion() {
        return env("AICABINET_SIM_APP_VERSION", "0.9.0");
    }

    private static String storageScheme() {
        return env("OBJECT_STORAGE_SCHEME", "minio");
    }

    private static String bucket() {
        return env("MINIO_BUCKET", DEFAULT_BUCKET);
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v.trim() : defaultValue;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".bin" : name.substring(dot);
    }

    private static String contentType(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            case ".mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    public static void main(String[] args) throws Exception {
        String deviceId = args.length > 0 ? args[0] : "CAB-001";
        String broker = args.length > 1 ? args[1] : "tcp://localhost:11883";
        new DeviceSimulator(deviceId).start(broker);
        Thread.currentThread().join();
    }

    private record VideoPayload(String primaryUri, String clipsJson, String fusionMode) {}
}
