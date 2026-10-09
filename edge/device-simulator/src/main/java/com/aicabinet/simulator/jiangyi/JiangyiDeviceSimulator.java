package com.aicabinet.simulator.jiangyi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * 将邑开门柜模拟器（CB-022，方案 §7）：按模式一协议走通「取 token → WS 在线 →
 * 收 openDoor → 拉门上报 → 识别上报」全链路，供一期验收（§9 步骤 12）。
 *
 * <p>与 DeviceSimulator（MQTT/chzh8）完全独立：将邑是 HTTP+WS 协议，无 MQTT 面。</p>
 *
 * <p>环境变量：</p>
 * <ul>
 *   <li>{@code JIANGYI_GATEWAY_BASE} — gateway 基址，默认 {@code http://localhost:18084}
 *       （compose 端口；联调期填穿透域名 https://xxx）</li>
 *   <li>{@code JIANGYI_DEVICE_SN} — 设备 SN，默认 {@code 2b26552554fb7bf9}（测试柜工控机）</li>
 *   <li>{@code JIANGYI_SIM_CLASS_ID} — 模拟识别 classId，默认 {@code 1}</li>
 *   <li>{@code JIANGYI_SIM_QTY} — 模拟数量，默认 {@code 1}</li>
 *   <li>{@code JIANGYI_SIM_SHOPPING_MS} — 拉门到识别上报的间隔，默认 {@code 3000}</li>
 * </ul>
 *
 * <p>用法：{@code java -jar device-simulator.jar jiangyi}（模块 shade 入口见 pom exec 配置，
 * 或直接运行主类）。</p>
 */
public class JiangyiDeviceSimulator implements WebSocket.Listener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String base;
    private final String deviceSn;
    private final int classId;
    private final int qty;
    private final long shoppingMs;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String token;
    private volatile String identifier;
    private final CountDownLatch keepAlive = new CountDownLatch(1);

    public JiangyiDeviceSimulator(String base, String deviceSn, int classId, int qty, long shoppingMs) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.deviceSn = deviceSn;
        this.classId = classId;
        this.qty = qty;
        this.shoppingMs = shoppingMs;
    }

    public static void main(String[] args) throws Exception {
        String base = env("JIANGYI_GATEWAY_BASE", "http://localhost:18084");
        String deviceSn = env("JIANGYI_DEVICE_SN", "2b26552554fb7bf9");
        int classId = Integer.parseInt(env("JIANGYI_SIM_CLASS_ID", "1"));
        int qty = Integer.parseInt(env("JIANGYI_SIM_QTY", "1"));
        long shoppingMs = Long.parseLong(env("JIANGYI_SIM_SHOPPING_MS", "3000"));
        new JiangyiDeviceSimulator(base, deviceSn, classId, qty, shoppingMs).run();
    }

    private void run() throws Exception {
        log("启动：base=" + base + " sn=" + deviceSn);
        // ① 取 token（V16 §4.2.3：设备以 SN 换 token）
        fetchToken();
        // ② WS 在线（wss://…/websocket/device/{identifier}?token=）
        connectWebSocket();
        log("在线等待 openDoor 指令…（Ctrl+C 退出）");
        keepAlive.await();
    }

    /** ③ 收到购物开门指令 → 模拟用户：开锁 → 拉门 → 购物 → 关门识别。 */
    private void onOpenDoor(String sessionId) {
        log("收到 openDoor orderNo=" + sessionId + "（=我方 sessionId）");
        try {
            post("/jiangyi/api/device/uploadLockState",
                    body().put("orderNo", sessionId).put("lockStatus", "success"));
            log("已上报开锁成功 lockStatus=success");
            post("/jiangyi/api/device/uploadDoorState",
                    body().put("orderNo", sessionId).put("doorStatus", "success"));
            log("已上报拉门 doorStatus=success，购物 " + shoppingMs + "ms…");
            Thread.sleep(shoppingMs);
            ObjectNode form = MAPPER.createObjectNode();
            form.put("classId", classId);
            form.put("quantity", qty);
            ArrayNode forms = MAPPER.createArrayNode().add(form);
            ObjectNode recognition = body().put("orderNo", sessionId);
            recognition.set("forms", forms);
            post("/jiangyi/api/orderProduct/addRecognitionGoodsToOrder", recognition);
            log("已上报识别结果 forms=[{classId=" + classId + ", quantity=" + qty + "}]，流程结束（结算在 trade 侧）");
        } catch (Exception e) {
            log("模拟流程失败：" + e.getMessage());
        }
    }

    // ---------- 基础设施 ----------

    private void fetchToken() throws Exception {
        JsonNode resp = post("/jiangyi/api/token/openDoorDeviceStatus",
                body().put("deviceSn", deviceSn), false);
        JsonNode data = resp.path("data");
        if (resp.path("status").asInt() != 200 || data.path("token").asText("").isBlank()) {
            throw new IllegalStateException("取 token 失败：" + resp);
        }
        this.token = data.path("token").asText();
        this.identifier = data.path("identifier").asText("");
        log("token 已获取 identifier=" + identifier);
    }

    private void connectWebSocket() throws Exception {
        String wsBase = base.replaceFirst("^http", "ws");
        URI uri = URI.create(wsBase + "/websocket/device/" + identifier + "?token=" + token);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .get();
        log("WS 已连接 " + uri);
    }

    private JsonNode post(String path, ObjectNode body) throws Exception {
        return post(path, body, true);
    }

    private JsonNode post(String path, ObjectNode payload, boolean withAuth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
        if (withAuth) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = MAPPER.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
        log("POST " + path + " → " + resp.statusCode() + " " + resp.body());
        return node;
    }

    private static ObjectNode body() {
        return MAPPER.createObjectNode();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static void log(String msg) {
        System.out.println("[jiangyi-sim] " + msg);
    }

    // ---------- WebSocket.Listener ----------

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonNode node = MAPPER.readTree(data.toString());
            if ("openDoor".equalsIgnoreCase(node.path("msgType").asText())) {
                String sessionId = node.path("msgContent").asText("");
                if (sessionId.isBlank()) {
                    log("收到空 msgContent（强制开门）——一期不支持，忽略");
                } else {
                    new Thread(() -> onOpenDoor(sessionId), "jiangyi-sim-flow").start();
                }
            } else {
                log("WS 下行：" + data);
            }
        } catch (Exception e) {
            log("WS 下行解析失败：" + data);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log("WS 关闭 statusCode=" + statusCode + " reason=" + reason);
        keepAlive.countDown();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log("WS 错误：" + error.getMessage());
        keepAlive.countDown();
    }
}
