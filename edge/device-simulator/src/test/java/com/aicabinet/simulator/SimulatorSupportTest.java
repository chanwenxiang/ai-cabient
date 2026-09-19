package com.aicabinet.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SimulatorSupport} 的纯函数单测。
 *
 * <p>这是 {@code edge/} 下的**第一批测试资产**（此前 {@code src/test} 完全为空）。
 * 之所以能测，是因为这些逻辑已从 845 行的 {@code DeviceSimulator}（构造即连 MQTT broker）
 * 抽成不碰网络/时钟/文件系统的纯函数。
 *
 * <p>除常规路径外，本测试**刻意固化若干既有边界行为**（点开头文件名、URL 解析失败原样返回等）——
 * 它们看起来"怪"但不是 bug，写成断言是为了让后续修改者一眼看到「这是有意为之」，
 * 而不是顺手"修"掉一个别人依赖的行为。
 */
class SimulatorSupportTest {

    private static Map<String, String> envOf(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("env：取环境变量")
    class EnvTest {

        @Test
        @DisplayName("键存在且非空白时返回 trim 后的值")
        void returnsTrimmedValue() {
            assertEquals("tcp://broker:1883", SimulatorSupport.env(envOf("MQTT_URL", "  tcp://broker:1883  "), "MQTT_URL", "fallback"));
        }

        @Test
        @DisplayName("键缺失时回落默认值")
        void fallsBackWhenMissing() {
            assertEquals("fallback", SimulatorSupport.env(envOf(), "MQTT_URL", "fallback"));
        }

        @Test
        @DisplayName("值为空串或全空白时回落默认值（等于「没配」）")
        void fallsBackWhenBlank() {
            assertEquals("fallback", SimulatorSupport.env(envOf("K", ""), "K", "fallback"));
            assertEquals("fallback", SimulatorSupport.env(envOf("K", "   "), "K", "fallback"));
        }

        @Test
        @DisplayName("source 或 key 为 null 时回落默认值，不抛 NPE")
        void toleraitesNullInputs() {
            assertEquals("fallback", SimulatorSupport.env(null, "K", "fallback"));
            assertEquals("fallback", SimulatorSupport.env(envOf("K", "v"), null, "fallback"));
        }

        @Test
        @DisplayName("默认值允许为 null（调用方用它判断「未配置」）")
        void allowsNullDefault() {
            assertEquals(null, SimulatorSupport.env(envOf(), "MINIO_ENDPOINT", null));
        }
    }

    @Nested
    @DisplayName("parseDoubleOrDefault：解析 double 配置")
    class ParseDoubleTest {

        @Test
        @DisplayName("合法数字正常解析（含负数与小数）")
        void parsesNumbers() {
            assertEquals(12.0, SimulatorSupport.parseDoubleOrDefault("12", -1), 1e-9);
            assertEquals(8.5, SimulatorSupport.parseDoubleOrDefault("8.5", -1), 1e-9);
            assertEquals(-3.25, SimulatorSupport.parseDoubleOrDefault("-3.25", -1), 1e-9);
        }

        @Test
        @DisplayName("非数字回落默认值而不是抛 NumberFormatException")
        void fallsBackOnGarbage() {
            assertEquals(12.0, SimulatorSupport.parseDoubleOrDefault("abc", 12), 1e-9);
            assertEquals(12.0, SimulatorSupport.parseDoubleOrDefault("", 12), 1e-9);
            assertEquals(12.0, SimulatorSupport.parseDoubleOrDefault(" ", 12), 1e-9);
        }

        @Test
        @DisplayName("null 回落默认值")
        void fallsBackOnNull() {
            assertEquals(8.0, SimulatorSupport.parseDoubleOrDefault(null, 8), 1e-9);
        }
    }

    @Nested
    @DisplayName("extension：取文件扩展名")
    class ExtensionTest {

        @Test
        @DisplayName("普通文件名取扩展名（含点）")
        void takesExtensionWithDot() {
            assertEquals(".jpg", SimulatorSupport.extension(Path.of("shot.jpg")));
            assertEquals(".mp4", SimulatorSupport.extension(Path.of("/data/clips/top.mp4")));
        }

        @Test
        @DisplayName("多段点号只取最后一段")
        void takesLastSegment() {
            assertEquals(".png", SimulatorSupport.extension(Path.of("a.b.c.png")));
        }

        @Test
        @DisplayName("无点号回落 .bin")
        void fallsBackToBinWhenNoDot() {
            assertEquals(".bin", SimulatorSupport.extension(Path.of("README")));
        }

        @Test
        @DisplayName("固化既有行为：点开头的文件名返回整个名字")
        void dotFileReturnsWholeName() {
            // lastIndexOf('.') == 0 ⇒ substring(0) == 整个名字。看起来像 bug，但这是抽取前就有的行为。
            assertEquals(".gitignore", SimulatorSupport.extension(Path.of(".gitignore")));
        }
    }

    @Nested
    @DisplayName("contentType：扩展名转 MIME")
    class ContentTypeTest {

        @Test
        @DisplayName("常见媒体类型映射正确")
        void mapsCommonTypes() {
            assertEquals("image/jpeg", SimulatorSupport.contentType(".jpg"));
            assertEquals("image/png", SimulatorSupport.contentType(".png"));
            assertEquals("image/webp", SimulatorSupport.contentType(".webp"));
            assertEquals("video/mp4", SimulatorSupport.contentType(".mp4"));
        }

        @Test
        @DisplayName("jpeg 与 jpg 等价")
        void jpegEqualsJpg() {
            assertEquals(SimulatorSupport.contentType(".jpg"), SimulatorSupport.contentType(".jpeg"));
        }

        @Test
        @DisplayName("大小写不敏感")
        void isCaseInsensitive() {
            assertEquals("image/jpeg", SimulatorSupport.contentType(".JPG"));
            assertEquals("image/jpeg", SimulatorSupport.contentType(".JpEg"));
            assertEquals("video/mp4", SimulatorSupport.contentType(".MP4"));
        }

        @Test
        @DisplayName("未知扩展名回落 octet-stream")
        void unknownFallsBack() {
            assertEquals("application/octet-stream", SimulatorSupport.contentType(".bin"));
            assertEquals("application/octet-stream", SimulatorSupport.contentType(".exe"));
            assertEquals("application/octet-stream", SimulatorSupport.contentType(""));
        }
    }

    @Nested
    @DisplayName("rewriteUploadUrl：presign URL 重写为容器内地址")
    class RewriteUploadUrlTest {

        private static final String SIGNED =
                "http://localhost:19000/cabinet-videos/sess-1/top.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123";

        @Test
        @DisplayName("未注入内部端点时原样返回（宿主直跑，公网 URL 本来就可达）")
        void passthroughWhenEndpointMissing() {
            assertEquals(SIGNED, SimulatorSupport.rewriteUploadUrl(SIGNED, null));
            assertEquals(SIGNED, SimulatorSupport.rewriteUploadUrl(SIGNED, ""));
            assertEquals(SIGNED, SimulatorSupport.rewriteUploadUrl(SIGNED, "   "));
        }

        @Test
        @DisplayName("重写 host 与 port，且签名 query 与 path 一字不动")
        void rewritesHostAndPortKeepingSignature() {
            String out = SimulatorSupport.rewriteUploadUrl(SIGNED, "http://minio:9000");
            assertEquals(
                    "http://minio:9000/cabinet-videos/sess-1/top.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
                    out);
        }

        @Test
        @DisplayName("内部端点带尾随斜杠也能正确拼接（不会出现双斜杠）")
        void toleratesTrailingSlash() {
            String out = SimulatorSupport.rewriteUploadUrl(SIGNED, "http://minio:9000/");
            assertEquals(
                    "http://minio:9000/cabinet-videos/sess-1/top.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
                    out);
        }

        @Test
        @DisplayName("固化既有行为：内部端点未写端口时会补默认端口，并把它**显式写进 URL**（http→:80 / https→:443）")
        void fillsDefaultPort() {
            // 注意 URL 里带 :80 / :443 —— 这是既有行为（构造 URI 时 port 已被解析成具体值），
            // 不是「多余端口」。抽取重构刻意保留原语义，故断言如此。
            assertEquals(
                    "http://minio:80/cabinet-videos/sess-1/top.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
                    SimulatorSupport.rewriteUploadUrl(SIGNED, "http://minio"));
            assertEquals(
                    "https://minio:443/cabinet-videos/sess-1/top.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
                    SimulatorSupport.rewriteUploadUrl(SIGNED, "https://minio"));
        }

        @Test
        @DisplayName("保留 presign URL 里的 userInfo")
        void keepsUserInfo() {
            String withUser = "http://user:pass@localhost:19000/bucket/k.jpg?X-Amz-Signature=s";
            assertEquals("http://user:pass@minio:9000/bucket/k.jpg?X-Amz-Signature=s",
                    SimulatorSupport.rewriteUploadUrl(withUser, "http://minio:9000"));
        }

        @Test
        @DisplayName("固化既有行为：presign URL 不可解析时原样返回，不抛异常中断上传")
        void passthroughOnUnparsableUrl() {
            String broken = "not a url at all";
            assertEquals(broken, SimulatorSupport.rewriteUploadUrl(broken, "http://minio:9000"));
        }

        @Test
        @DisplayName("固化既有行为：内部端点不可解析时也原样返回")
        void passthroughOnUnparsableEndpoint() {
            assertEquals(SIGNED, SimulatorSupport.rewriteUploadUrl(SIGNED, "::::not-an-endpoint"));
        }
    }

    /**
     * 上行报文构造（P0-7 抽取出来的三个纯函数）。
     *
     * <p>本组断言的是**线上报文的真实形态**，故 {@code type} 一律断字面量而非
     * {@code CabinetConstants.MQTT_EVENT_TYPE_*} —— 这些字符串是**跨进程/跨语言**契约
     * （真机端是 Kotlin 独立实现、不共享该常量类），用共享常量自证等于没证。
     *
     * <p>与 device-service 的 {@code EdgeCloudMqttContractTest} 互补：那边验「云端能不能吃」
     * （真解析器消费真报文），这边验「模拟器发的是什么」。
     */
    @Nested
    @DisplayName("上行报文构造：字段名/值与抽取前逐字一致")
    class PayloadConstructionTest {

        @Test
        @DisplayName("ack：4 个字段，值与契约一致")
        void ackFields() {
            Map<String, Object> p = SimulatorSupport.ackPayload("cmd-1", true, 1_700_000_000_003L);
            assertEquals(4, p.size());
            assertEquals("ACK", p.get("type"));
            assertEquals("cmd-1", p.get("commandId"));
            assertEquals(Boolean.TRUE, p.get("success"));
            assertEquals(Long.valueOf(1_700_000_000_003L), p.get("timestamp"));
        }

        @Test
        @DisplayName("🔴 ack 刻意保留 Map.of 的严格语义：commandId 为 null ⇒ NPE（与抽取前逐字一致）")
        void ackKeepsStrictMapOfSemanticsOnNull() {
            // 这不是缺陷，而是「抽取重构不夹带行为变更」的结果。
            // 若有人把实现改成 LinkedHashMap，本用例转红 —— 那时请先确认是否有意改变契约，
            // 而不是把断言删掉了事（理由写在 SimulatorSupport#ackPayload 的 Javadoc 里）。
            assertThrows(NullPointerException.class,
                    () -> SimulatorSupport.ackPayload(null, true, 1L));
        }

        @Test
        @DisplayName("door：必填 4 键；可选字段为 null 时**整个键不写入**（云端 node.path 取到空）")
        void doorEventOmitsNullOptionalKeys() {
            Map<String, Object> min = SimulatorSupport.doorEventPayload(
                    "S-MIN", "OPEN", 1L, null, null, null, null, null);
            assertEquals(4, min.size());
            assertFalse(min.containsKey("videoUri"));
            assertFalse(min.containsKey("uploadStatus"));
            assertFalse(min.containsKey("videoClipsJson"));
            assertFalse(min.containsKey("cameraFusionMode"));
            assertFalse(min.containsKey("gravityDeltasJson"));

            Map<String, Object> full = SimulatorSupport.doorEventPayload(
                    "S-FULL", "CLOSED", 1L, "u", "UPLOADED", "[]", "MULTI", "[]");
            assertEquals(9, full.size());
            assertEquals("DOOR", full.get("type"));
            assertEquals("S-FULL", full.get("sessionId"));
            assertEquals("CLOSED", full.get("doorState"));
        }

        @Test
        @DisplayName("door：刻意**不含 deviceId**（设备身份由 topic 承载，body 里出现即违约）")
        void doorEventNeverCarriesDeviceId() {
            Map<String, Object> p = SimulatorSupport.doorEventPayload(
                    "S-1", "CLOSED", 1L, "u", "UPLOADED", "[]", "MULTI", "[]");
            assertFalse(p.containsKey("deviceId"),
                    "deviceId 走 topic（cabinet/{deviceId}/evt），报文体里不得重复 —— 云端以 topic 为准");
        }

        @Test
        @DisplayName("heartbeat：camelCase 字段名（云端优先分支）+ deviceId 在报文体内")
        void heartbeatFields() {
            Map<String, Object> p = SimulatorSupport.heartbeatPayload(
                    "DEV-1", 2L, "0.9.0", "1.0.0", 12);
            assertEquals(6, p.size());
            assertEquals("HEARTBEAT", p.get("type"));
            assertEquals("DEV-1", p.get("deviceId"));
            assertEquals("0.9.0", p.get("appVersion"));
            assertEquals("1.0.0", p.get("firmwareVersion"));
            assertEquals(Integer.valueOf(12), p.get("currentTempC"));
            // snake_case 是云端保留给老固件的兼容分支，模拟器**发的是** camelCase
            assertFalse(p.containsKey("app_version"));
            assertFalse(p.containsKey("current_temp_c"));
        }
    }
}
