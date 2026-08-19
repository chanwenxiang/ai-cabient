package com.aicabinet.simulator;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.mqtt.MqttTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

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
 *   <li>{@code AICABINET_SIM_SHOPPING_MS} — 开门后自动关门等待；{@code 0} 表示保持开门，需点 HTTP「关门」或走小程序结算</li>
 *   <li>{@code AICABINET_SIM_HTTP_PORT} — 测试用关门页端口，默认 {@code 18089}；{@code 0} 关闭</li>
 * </ul>
 */
public class DeviceSimulator implements MqttCallbackExtended {

    /** 1x1 JPEG；无素材或上传失败回退时写入桶内，避免会话挂空 minio:// URI。 */
    private static final byte[] PLACEHOLDER_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00, 0x08,
            0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D,
            0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C,
            0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30,
            0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
            (byte) 0xFF, (byte) 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            (byte) 0xFF, (byte) 0xC4, 0x00, 0x14, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,
            (byte) 0xFF, (byte) 0xC4, 0x00, 0x14, 0x10, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x7F,
            (byte) 0xFF, (byte) 0xD9
    };

    private final String deviceId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Object doorLock = new Object();
    private MqttClient client;
    private boolean lastOperatorMode;
    private long lastUserId;
    /** {@code SHOPPING_MS=0} 时记录当前开门会话，供 HTTP /close 手动关门。 */
    private volatile String openSessionId;

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
        subscribeCommands();
        logVideoConfig();
        checkOta();
        publishHeartbeat();
        startHeartbeatLoop();
        startHttpControl();
    }

    private void subscribeCommands() throws MqttException {
        String topic = MqttTopics.command(deviceId);
        client.subscribe(topic, 1);
        System.out.println("[simulator] subscribed " + topic);
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
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            subscribeCommands();
            System.out.println("[simulator] " + (reconnect ? "re" : "") + "connected to " + serverURI
                    + ", resubscribed commands");
            if (reconnect) {
                publishHeartbeat();
            }
        } catch (MqttException e) {
            System.err.println("[simulator] failed to resubscribe after reconnect: " + e.getMessage());
        }
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
                if (shoppingMs <= 0) {
                    openSessionId = sessionId;
                    System.out.println("[simulator] door OPEN session=" + sessionId
                            + " — waiting for HTTP /close (no auto-close)");
                    return;
                }
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
        try {
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
        } finally {
            if (sessionId != null && sessionId.equals(openSessionId)) {
                openSessionId = null;
            }
        }
    }

    /** 测试页：手动触发关门（SHOPPING_MS=0 时用）。端口 {@code AICABINET_SIM_HTTP_PORT}，0 关闭。 */
    private void startHttpControl() {
        int port;
        try {
            port = Integer.parseInt(env("AICABINET_SIM_HTTP_PORT", "18089"));
        } catch (NumberFormatException e) {
            System.err.println("[simulator] invalid AICABINET_SIM_HTTP_PORT, HTTP control disabled");
            return;
        }
        if (port <= 0) {
            System.out.println("[simulator] HTTP control disabled (port<=0)");
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handleHttpRoot);
            server.createContext("/status", this::handleHttpStatus);
            server.createContext("/close", this::handleHttpClose);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sim-http-" + deviceId);
                t.setDaemon(true);
                return t;
            }));
            server.start();
            System.out.println("[simulator] HTTP control http://127.0.0.1:" + port
                    + "/  (GET/POST /close, GET /status)");
        } catch (Exception e) {
            System.err.println("[simulator] HTTP control failed: " + e.getMessage());
        }
    }

    private void handleHttpRoot(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeText(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String sid = openSessionId;
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1"/>
                  <title>模拟器关门 · %s</title>
                  <style>
                    body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;
                         display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
                    .card{background:#1e293b;padding:28px 32px;border-radius:16px;width:min(420px,92vw);
                          box-shadow:0 12px 40px rgba(0,0,0,.35)}
                    h1{font-size:1.25rem;margin:0 0 8px}
                    p{margin:0 0 20px;color:#94a3b8;font-size:.95rem;word-break:break-all}
                    button{width:100%%;border:0;border-radius:12px;padding:16px;font-size:1.1rem;
                           font-weight:600;cursor:pointer;background:#10b981;color:#042f2e}
                    button:disabled{opacity:.45;cursor:not-allowed}
                    .ok{color:#6ee7b7}.warn{color:#fbbf24}.err{color:#fca5a5}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>设备 %s</h1>
                    <p id="state">%s</p>
                    <button id="btn" %s onclick="closeDoor()">关门结算</button>
                    <p id="msg" style="margin-top:16px;margin-bottom:0"></p>
                  </div>
                  <script>
                    async function closeDoor(){
                      const btn=document.getElementById('btn');
                      const msg=document.getElementById('msg');
                      btn.disabled=true; msg.textContent='关门中…'; msg.className='';
                      try{
                        const r=await fetch('/close',{method:'POST'});
                        const t=await r.text();
                        msg.textContent=t;
                        msg.className=r.ok?'ok':'err';
                        if(r.ok){ document.getElementById('state').textContent='门已关，无进行中会话'; }
                        else { btn.disabled=false; }
                      }catch(e){ msg.textContent=String(e); msg.className='err'; btn.disabled=false; }
                    }
                    setInterval(async()=>{
                      try{
                        const r=await fetch('/status'); const j=await r.json();
                        const el=document.getElementById('state');
                        const btn=document.getElementById('btn');
                        if(j.open){ el.textContent='开门中 · session='+j.sessionId; el.className='warn'; btn.disabled=false; }
                        else { el.textContent='门已关 / 无进行中会话'; el.className=''; }
                      }catch(_){}
                    }, 2000);
                  </script>
                </body>
                </html>
                """.formatted(
                deviceId,
                deviceId,
                sid == null || sid.isBlank()
                        ? "门已关 / 无进行中会话"
                        : "开门中 · session=" + sid,
                sid == null || sid.isBlank() ? "disabled" : "");
        writeText(ex, 200, "text/html; charset=utf-8", html);
    }

    private void handleHttpStatus(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeText(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String sid = openSessionId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", deviceId);
        body.put("open", sid != null && !sid.isBlank());
        body.put("sessionId", sid);
        writeText(ex, 200, "application/json; charset=utf-8", mapper.writeValueAsString(body));
    }

    private void handleHttpClose(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
            writeText(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        synchronized (doorLock) {
            String sid = openSessionId;
            if (sid == null || sid.isBlank()) {
                writeText(ex, 409, "text/plain; charset=utf-8", "无开门会话，无需关门");
                return;
            }
            try {
                completeDoorClose(sid);
                writeText(ex, 200, "text/plain; charset=utf-8", "已关门 session=" + sid);
            } catch (Exception e) {
                writeText(ex, 500, "text/plain; charset=utf-8",
                        "关门失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
    }

    private static void writeText(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
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
                // 补货/运营开门以关门事件为主；上传失败时改传占位图，避免空 minio:// 与会话卡死
                if (lastOperatorMode) {
                    System.err.println("[simulator] upload failed (operatorMode), uploading placeholder: " + e.getMessage());
                    try {
                        return uploadPlaceholder(sessionId, camera);
                    } catch (Exception placeholderEx) {
                        throw new RuntimeException("failed to upload sim placeholder after media failure", placeholderEx);
                    }
                }
                throw new RuntimeException("failed to upload sim video", e);
            }
        }

        try {
            System.out.println("[simulator] AICABINET_SIM_VIDEO_FILE unset, uploading placeholder for " + camera);
            return uploadPlaceholder(sessionId, camera);
        } catch (Exception e) {
            throw new RuntimeException("failed to upload sim placeholder", e);
        }
    }

    private String uploadPlaceholder(String sessionId, String camera) throws Exception {
        String uri = uploadBytesViaPresign(PLACEHOLDER_JPEG, ".jpg", "image/jpeg", sessionId, lastUserId, camera);
        System.out.println("[simulator] uploaded placeholder -> " + uri);
        return uri;
    }

    private String uploadViaPresign(Path localPath, String sessionId, long userId, String camera) throws Exception {
        if (!Files.isRegularFile(localPath)) {
            throw new IllegalArgumentException("video file not found: " + localPath.toAbsolutePath());
        }
        String ext = extension(localPath);
        String uri = uploadBytesViaPresign(Files.readAllBytes(localPath), ext, contentType(ext), sessionId, userId, camera);
        System.out.println("[simulator] uploaded " + localPath + " -> " + uri);
        return uri;
    }

    private String uploadBytesViaPresign(byte[] bytes, String ext, String contentType,
                                         String sessionId, long userId, String camera) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty upload payload");
        }
        String tradeUrl = env("TRADE_SERVICE_URL", "http://localhost:8080");
        String apiKey = env("INTERNAL_API_KEY", "dev-internal-key-change-me");
        String normalizedExt = ext == null || ext.isBlank() ? ".bin" : (ext.startsWith(".") ? ext : "." + ext);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("deviceId", deviceId);
        body.put("userId", userId);
        body.put("camera", camera);
        body.put("extension", normalizedExt);
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
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(120))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> putRes = http.send(putReq, HttpResponse.BodyHandlers.discarding());
        if (putRes.statusCode() < 200 || putRes.statusCode() >= 300) {
            throw new IllegalStateException(
                    "presign upload HTTP " + putRes.statusCode() + " urlHost=" + URI.create(uploadUrl).getHost());
        }
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
        return Long.parseLong(env("AICABINET_SIM_SHOPPING_MS", "0"));
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
