package com.aicabinet.simulator;

import com.aicabinet.common.constants.CabinetConstants;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link DeviceSimulator} 里**可脱离 IO 独立测试**的纯函数。
 *
 * <p>抽出来的动机：这些逻辑原先都是 {@code DeviceSimulator} 的 private static 方法，而该类是
 * 「MQTT 连接 + HTTP 服务 + 上传编排」的重量级对象（845 行、构造即连 broker），
 * 单测里无法构造 ⇒ {@code edge/} 长期零测试资产。这里只放**不碰网络、时钟、文件系统**的部分，
 * 全部是纯函数，可由 {@link SimulatorSupportTest} 直接断言。
 *
 * <p>行为契约与抽取前**逐字一致**（含边界行为），抽取只是把「不可测」变成「可测」，不改变运行语义。
 */
public final class SimulatorSupport {

    private SimulatorSupport() {}

    /**
     * 环境变量取值：缺失或**空白**一律回落到默认值；非空白则 trim。
     *
     * <p>空白也回落是有意的 —— {@code AICABINET_SIM_APP_VERSION=""} 这类空配置应当等于「没配」，
     * 而不是得到一个空字符串版本号；trim 则避免 {@code "true "} 被判成 false。
     */
    public static String env(Map<String, String> source, String key, String defaultValue) {
        if (source == null || key == null) {
            return defaultValue;
        }
        String v = source.get(key);
        return v != null && !v.isBlank() ? v.trim() : defaultValue;
    }

    /**
     * 解析 double：null 或不可解析时回落默认值。
     *
     * <p>刻意吞掉 {@link NumberFormatException} —— 模拟器是演示/联调工具，
     * 一个手抖的温度配置不应该让它直接起不来。
     */
    public static double parseDoubleOrDefault(String value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 取文件名扩展名（**含点**）；无点回落 {@code ".bin"}。
     *
     * <p>⚠️ 既有边界行为：{@code ".gitignore"} 这类**点开头**的文件名会返回整个名字
     * （因为 {@code lastIndexOf('.') == 0}）—— 不是 bug，是提取前就有的事实，测试里固化它以防误改。
     */
    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".bin" : name.substring(dot);
    }

    /** 扩展名 → MIME（**大小写不敏感**）；未知类型回落 {@code application/octet-stream}。 */
    public static String contentType(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            case ".mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    /**
     * 把 presign 出来的**公网** URL 重写成**容器内可达**的地址（{@code MINIO_ENDPOINT}），
     * 保留签名 query / userInfo / fragment。
     *
     * <p>为什么需要：MinIO 的 presign URL 里 host 是给浏览器用的公网地址（如 {@code localhost:19000}），
     * 而模拟器跑在容器里时那个地址**不可达** ⇒ 上传必然失败。
     *
     * <p>两条「宁可退化也不中断」的路径：
     * <ul>
     *   <li>未注入 {@code internalEndpoint}（null / 空白）⇒ **原样返回**（宿主直跑场景，公网 URL 本来就可达）；</li>
     *   <li>URL 解析失败 ⇒ **原样返回**。传一个可能不可达的地址，也好过因重写抛错直接中断上传。</li>
     * </ul>
     */
    public static String rewriteUploadUrl(String presignedUrl, String internalEndpoint) {
        if (internalEndpoint == null || internalEndpoint.isBlank()) {
            return presignedUrl;
        }
        try {
            String base = internalEndpoint.endsWith("/")
                    ? internalEndpoint.substring(0, internalEndpoint.length() - 1)
                    : internalEndpoint;
            URI internal = URI.create(base);
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

    // ---------------------------------------------------------------------
    // 上行报文构造（P0-7：抽成纯函数，使「模拟器 ↔ 云端」契约可被自动化断言）
    //
    // 动机：这些字段名是**三方约定**（模拟器 / edge 真机 / device-service 解析器），
    // 而原先它们散在 DeviceSimulator 的 private publish* 方法里 —— 那里带 MQTT 连接、
    // 造不出来实例，契约只能靠人眼比对。抽出来后既可由 DeviceSimulator 复用，
    // 也可由 device-service 的契约测试**用真报文喂真解析器**，改坏任一侧即红。
    //
    // 🔴 字段名/类型/取值语义与抽取前**完全一致**（无任何有意差异）：
    //    doorEvent/heartbeat 沿用抽取前的 LinkedHashMap，ack 沿用抽取前的 Map.of —— 见各函数 Javadoc。
    //    可用性 / 键序差异随之逐字保留（含 ack 的 null ⇒ NPE 严格语义）。
    // ---------------------------------------------------------------------

    /**
     * DOOR 事件上行报文（{@code type=DOOR}）—— 对应 device-service
     * {@code MqttEventListener.handleDoorEvent} 的消费字段。
     *
     * <p>⚠️ 刻意**不含 {@code deviceId}**：设备身份由 topic（{@code cabinet/{deviceId}/evt}）承载，
     * 云端在 body 缺省时以 topic 为准。可选字段为 {@code null} 时**整个键不写入**，
     * 与抽取前逐字一致（云端用 {@code node.path(x)} 取值，缺键即取到空串/null）。
     */
    public static Map<String, Object> doorEventPayload(
            String sessionId,
            String doorStateName,
            long timestamp,
            String videoUri,
            String uploadStatus,
            String videoClipsJson,
            String cameraFusionMode,
            String gravityDeltasJson) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", CabinetConstants.MQTT_EVENT_TYPE_DOOR);
        data.put("sessionId", sessionId);
        data.put("doorState", doorStateName);
        data.put("timestamp", timestamp);
        if (videoUri != null) {
            data.put("videoUri", videoUri);
        }
        if (uploadStatus != null) {
            data.put("uploadStatus", uploadStatus);
        }
        if (videoClipsJson != null) {
            data.put("videoClipsJson", videoClipsJson);
        }
        if (cameraFusionMode != null) {
            data.put("cameraFusionMode", cameraFusionMode);
        }
        if (gravityDeltasJson != null) {
            data.put("gravityDeltasJson", gravityDeltasJson);
        }
        return data;
    }

    /**
     * 心跳上行报文（{@code type=HEARTBEAT}）—— 对应 {@code MqttEventListener.handleHeartbeat}。
     *
     * <p>字段名取 **camelCase**（{@code appVersion}/{@code firmwareVersion}/{@code currentTempC}）：
     * 云端同时接受 snake_case 变体并**优先 camelCase**，此处发的是优先分支。
     */
    public static Map<String, Object> heartbeatPayload(
            String deviceId,
            long timestamp,
            String appVersion,
            String firmwareVersion,
            Integer currentTempC) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT);
        payload.put("deviceId", deviceId);
        payload.put("timestamp", timestamp);
        payload.put("appVersion", appVersion);
        payload.put("firmwareVersion", firmwareVersion);
        payload.put("currentTempC", currentTempC);
        return payload;
    }

    /**
     * 指令 ACK 上行报文（{@code type=ACK}）—— 对应 {@code MqttEventListener.handleAck}
     * 与 {@code DeviceCommandTracker.recordAck}。
     *
     * <p>🔴 本函数用 {@code Map.of(...)} 而**不是** {@code LinkedHashMap}，尽管后者"更好看"
     * （键序固定、null 不炸）。选前者是为了与抽取前的实现**逐字一致** —— 抽取重构不该夹带行为变更。
     * 两处差异均为**接受项**，理由写在这里防止后人"顺手优化"：
     * <ul>
     *   <li>{@code commandId == null} 抛 {@link NullPointerException}（fail-fast 的严格语义）。
     *       生产路径**安全**：调用方传的是 Jackson {@code node.path("commandId").asText()}，
     *       字段缺失返回 {@code ""}、NullNode 返回字面量 {@code "null"}，**不会是 Java null**。</li>
     *   <li>键的迭代顺序未指定（{@code Map.of} 语义）。消费端一律按名取值
     *       （{@code node.path("success").asBoolean()}），不依赖顺序，故无语义影响。</li>
     * </ul>
     * <p>该严格语义由 {@code SimulatorSupportTest} 的 {@code ackKeepsStrictMapOfSemanticsOnNull} 钉住。
     */
    public static Map<String, Object> ackPayload(String commandId, boolean success, long timestamp) {
        return Map.of(
                "type", CabinetConstants.MQTT_EVENT_TYPE_ACK,
                "commandId", commandId,
                "success", success,
                "timestamp", timestamp);
    }
}
