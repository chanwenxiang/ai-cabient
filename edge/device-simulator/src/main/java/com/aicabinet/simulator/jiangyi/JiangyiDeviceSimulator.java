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
 *   <li>{@code JIANGYI_SIM_TEXT_NAME} —（二期）按 textName 从 classes.txt 对照行号取 classId
 *       （0-based，可用 {@code JIANGYI_SIM_CLASS_ID_BASE} 改 1-based）；设置后覆盖
 *       {@code JIANGYI_SIM_CLASS_ID}。classes 内容来自 updateModel 下发的 textUrl</li>
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
    private volatile WebSocket ws;
    /** 二期：最近一次 updateModel 的模型上下文（下载模拟 + classes 对照取 classId）。 */
    private volatile String currentModelName;
    private volatile String currentTextUrl;
    private volatile List<String> classesRows = List.of();
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
            int effectiveClassId = resolveClassId();
            ObjectNode form = MAPPER.createObjectNode();
            form.put("classId", effectiveClassId);
            form.put("quantity", qty);
            ArrayNode forms = MAPPER.createArrayNode().add(form);
            ObjectNode recognition = body().put("orderNo", sessionId);
            recognition.set("forms", forms);
            post("/jiangyi/api/orderProduct/addRecognitionGoodsToOrder", recognition);
            log("已上报识别结果 forms=[{classId=" + effectiveClassId + ", quantity=" + qty
                    + "}]，流程结束（结算在 trade 侧）");
        } catch (Exception e) {
            log("模拟流程失败：" + e.getMessage());
        }
    }

    /**
     * 二期：识别 classId 取值。设置 JIANGYI_SIM_TEXT_NAME 时按 classes.txt 行号对照
     * （classes 源自最近一次 updateModel 的 textUrl）；行不存在/未拉到 classes 则回落
     * 固定 JIANGYI_SIM_CLASS_ID 并告警。
     */
    private int resolveClassId() {
        String textName = env("JIANGYI_SIM_TEXT_NAME", "");
        if (textName.isBlank() || classesRows.isEmpty()) {
            return classId;
        }
        int base = Integer.parseInt(env("JIANGYI_SIM_CLASS_ID_BASE", "0"));
        for (int i = 0; i < classesRows.size(); i++) {
            if (classesRows.get(i).equals(textName)) {
                log("classes 对照：textName=\"" + textName + "\" → classId=" + (i + base)
                        + "（行 " + (i + 1) + "，base=" + base + "）");
                return i + base;
            }
        }
        log("警告：classes 中未找到 textName=\"" + textName + "\"，回落固定 classId=" + classId);
        return classId;
    }

    /** 二期（V16 §4.3.2.9）：收到 updateModel → 模拟下载 model/text → 上报 downloadModelNotify。 */
    private void onUpdateModel(JsonNode content) {
        try {
            String modelName = content.path("modelName").asText("");
            String modelUrl = content.path("modelUrl").asText("");
            String textUrl = content.path("textUrl").asText("");
            int quantity = content.path("quantity").asInt(-1);
            log("收到 updateModel modelName=" + modelName + " quantity=" + quantity
                    + " modelUrl=" + modelUrl);
            // 模拟机器端下载：GET model 文件（只验证可达性，不落盘）
            if (!modelUrl.isBlank()) {
                HttpResponse<String> modelResp = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(modelUrl)).timeout(Duration.ofSeconds(30))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                log("模拟下载模型 " + modelUrl + " → " + modelResp.statusCode());
            }
            // 拉 classes.txt 建立行号对照（识别 classId 用）
            if (!textUrl.isBlank()) {
                HttpResponse<String> textResp = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(textUrl)).timeout(Duration.ofSeconds(30))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (textResp.statusCode() == 200 && textResp.body() != null) {
                    classesRows = java.util.Arrays.stream(textResp.body().split("\r?\n"))
                            .map(String::strip).filter(s -> !s.isEmpty()).toList();
                    currentTextUrl = textUrl;
                    log("classes 已缓存 " + classesRows.size() + " 行（前 3 行：" + classesRows.stream()
                            .limit(3).toList() + "…）");
                }
            }
            currentModelName = modelName;
            // 回执（V16 §4.2.5）：downloadModelNotify 走 WS 上行（gateway DeviceWebSocketHandler 解析），
            // 成功后机器端重启——模拟器不重启只回执；msgContent 形态按对象发（gateway 宽容解析兼容字符串）
            WebSocket ws = this.ws;
            if (ws != null) {
                ws.sendText(MAPPER.writeValueAsString(body()
                        .put("msgType", "downloadModelNotify")
                        .putPOJO("msgContent", MAPPER.createObjectNode().put("modelName", modelName))), true);
                log("已上报 downloadModelNotify（WS 上行）modelName=" + modelName);
            } else {
                log("WS 未连接，无法回执 downloadModelNotify");
            }
        } catch (Exception e) {
            log("updateModel 模拟失败：" + e.getMessage());
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
        this.ws = webSocket;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonNode node = MAPPER.readTree(data.toString());
            String msgType = node.path("msgType").asText();
            if ("openDoor".equalsIgnoreCase(msgType)) {
                String sessionId = node.path("msgContent").asText("");
                if (sessionId.isBlank()) {
                    log("收到空 msgContent（强制开门）——一期不支持，忽略");
                } else {
                    new Thread(() -> onOpenDoor(sessionId), "jiangyi-sim-flow").start();
                }
            } else if ("updateModel".equalsIgnoreCase(msgType)) {
                new Thread(() -> onUpdateModel(node.path("msgContent")), "jiangyi-sim-model").start();
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
