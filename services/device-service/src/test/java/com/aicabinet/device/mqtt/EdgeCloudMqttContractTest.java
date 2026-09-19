package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.config.MqttProperties;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import com.aicabinet.device.service.DeviceCommandTracker;
import com.aicabinet.simulator.SimulatorSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * P0-7：**模拟器 ↔ device-service ↔ trade-service** 上行契约。
 *
 * <p>与既有 {@code MqttEventListenerDoorTest} 的分工：那边测「云端解析的**行为**」（去重、重投、
 * 指标），这边测「**跨组件的字段/主题约定**」—— 也就是说，改坏「模拟器发什么」「云端收什么」
 * 任一侧，本类立刻红。
 *
 * <p>🔴 **报文一律由模拟器的真实构造器产出**（{@link SimulatorSupport#doorEventPayload} 等），
 * 不在测试里手抄 JSON —— 手抄出来的字段名改坏生产代码也不会红，是**假契约**。
 *
 * <p>覆盖不到的两处，由**同一个**静态门禁 {@code check:edge-cloud-mqtt-contract}
 * （{@code scripts/check-edge-cloud-mqtt-contract.mjs}，已入聚合链）兜住：
 * <ul>
 *   <li><b>真机端 Kotlin</b>（{@code edge/android-app} 无 gradlew/ANDROID_HOME，Maven 跑不到）
 *       ⇒ 其硬编码 topic 形状与 {@code payload.type} 取值，与 {@code MqttTopics} /
 *       {@code CabinetConstants} / {@code proto/cabinet.proto} **三方对账**；</li>
 *   <li><b>trade-service 侧端点</b>（本模块测试不依赖 trade-service）⇒ 门禁按源码把
 *       {@code TradeServiceClient} 的 {@code .uri("/internal/…")} 与对端
 *       {@code @RequestMapping} + {@code @XxxMapping} 拼出的路径逐个比对，
 *       拼不上即红（否则是**运行期静默 404**，编译期毫无提示）。</li>
 * </ul>
 *
 * <p>⚠️ 门禁只管**路径**能不能对上，不管 HTTP 方法与请求体形状（那要端到端测试兜）；
 * 报文**体内字段**（如 {@code alertType}）也不在它的覆盖面内。
 */
@ExtendWith(MockitoExtension.class)
class EdgeCloudMqttContractTest {

    private static final String DEVICE_ID = "CAB-CONTRACT-1";

    @Mock TradeServiceClient tradeServiceClient;
    @Mock MqttConnectOptionsFactory connectOptionsFactory;
    @Mock MqttConnectionRegistry connectionRegistry;
    @Mock DeviceCommandTracker commandTracker;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new MqttEventListener(
                objectMapper,
                tradeServiceClient,
                new MqttProperties("tcp://localhost:1883", "test", null, null, null, null, "target/mqtt-test"),
                connectOptionsFactory,
                connectionRegistry,
                new DeviceMqttMetrics(new SimpleMeterRegistry()),
                new DoorEventDeduplicator(),
                commandTracker);
    }

    // ---------------------------------------------------------------- DOOR

    /** 模拟器「关门」全字段报文 → trade 收到的 DTO 每个字段都必须落到同名位置。 */
    @Test
    void doorEvent_fullPayloadFromSimulator_allFieldsReachTradeDto() throws Exception {
        Map<String, Object> payload = SimulatorSupport.doorEventPayload(
                "S-CONTRACT-FULL", DoorState.CLOSED.name(), 1_700_000_000_000L,
                "minio://bucket/top.mp4", "UPLOADED",
                "[{\"camera\":\"TOP\"},{\"camera\":\"SIDE\"}]", "MULTI",
                "[{\"skuId\":\"SKU-1\",\"delta\":-1,\"slotId\":\"A1\"}]");

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        DoorEventRequest request = capturedDoorEvent();
        assertEquals("S-CONTRACT-FULL", request.sessionId());
        // 🔴 模拟器**不发** deviceId，云端必须以 topic 为准 —— 这是有意的契约（身份由 topic 承载）
        assertEquals(DEVICE_ID, request.deviceId());
        assertEquals(DoorState.CLOSED, request.doorState());
        // ⚠️ 报文里的 timestamp 云端**不读**，DTO 取服务端接收时刻 —— 事实由
        // doorEvent_reportTimestampIsIgnored_serverClockIsUsed 用例单独固化
        assertTrue(request.timestamp() > 0, "timestamp 由服务端填充");
        assertEquals("minio://bucket/top.mp4", request.videoUri());
        assertEquals("UPLOADED", request.uploadStatus());
        assertEquals("[{\"camera\":\"TOP\"},{\"camera\":\"SIDE\"}]", request.videoClipsJson());
        assertEquals("MULTI", request.cameraFusionMode());
        assertEquals("[{\"skuId\":\"SKU-1\",\"delta\":-1,\"slotId\":\"A1\"}]", request.gravityDeltasJson());
    }

    /** 模拟器「开门」最小报文（只有 4 个键）：可选字段为 null，且**不因缺 deviceId 被拒**。 */
    @Test
    void doorEvent_minimalPayloadFromSimulator_optionalFieldsNull() throws Exception {
        Map<String, Object> payload = SimulatorSupport.doorEventPayload(
                "S-CONTRACT-MIN", DoorState.OPEN.name(), 1_700_000_000_001L,
                null, null, null, null, null);

        assertEquals(4, payload.size(), "开门报文应只有 type/sessionId/doorState/timestamp 四个键");
        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        DoorEventRequest request = capturedDoorEvent();
        assertEquals("S-CONTRACT-MIN", request.sessionId());
        assertEquals(DoorState.OPEN, request.doorState());
        assertEquals(DEVICE_ID, request.deviceId());
        assertNull(request.videoUri());
        assertNull(request.uploadStatus());
        assertNull(request.videoClipsJson());
        assertNull(request.cameraFusionMode());
        assertNull(request.gravityDeltasJson());
    }

    /**
     * 云端**兼容层**契约：snake_case 变体同样被接受（老固件/其它厂商设备在用）。
     *
     * <p>这条**不是**模拟器发的形态（模拟器发 camelCase，见上一条），而是云端刻意保留的兼容分支；
     * 记录下来防止有人「顺手清理」成只认 camelCase 而打断在网老设备。
     */
    @Test
    void doorEvent_snakeCaseAliases_stillAcceptedByCloud() throws Exception {
        String payload = """
                {"type":"DOOR","sessionId":"S-ALIAS","doorState":"CLOSED",
                 "video_clips":"[{\\"camera\\":\\"TOP\\"}]","camera_fusion_mode":"SINGLE",
                 "gravity_deltas":"[{\\"skuId\\":\\"S9\\",\\"delta\\":-1}]","event_seq":"7"}
                """;

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        DoorEventRequest request = capturedDoorEvent();
        assertEquals("S-ALIAS", request.sessionId());
        assertEquals("[{\"camera\":\"TOP\"}]", request.videoClipsJson());
        assertEquals("SINGLE", request.cameraFusionMode());
        assertEquals("[{\"skuId\":\"S9\",\"delta\":-1}]", request.gravityDeltasJson());
    }

    /**
     * 🔴 **P0-7 发现的契约差异（本批只固化、不修改生产行为）**：报文里的 {@code timestamp} 被云端忽略，
     * DTO 的 timestamp 一律取**服务端接收时刻**（{@code MqttEventListener.handleDoorEvent} 里写死
     * {@code System.currentTimeMillis()}）。
     *
     * <p>后果：真机（{@code OutboundMqttQueue}）**断网补投**时，事件发生时间与入库时间可相差任意久，
     * 依赖 {@code DoorEventRequest.timestamp} 做时序归因/对账的下游会失真。
     *
     * <p>这条把「当前行为」钉在测试上：将来若改为采用报文时间，本用例转红 ⇒ 提醒同步更新契约说明。
     */
    @Test
    void doorEvent_reportTimestampIsIgnored_serverClockIsUsed() throws Exception {
        long reported = 1_700_000_000_000L;   // 与「现在」必然不同，用来证明该字段未被采用
        Map<String, Object> payload = SimulatorSupport.doorEventPayload(
                "S-TS-SNAPSHOT", DoorState.CLOSED.name(), reported, null, null, null, null, null);

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        DoorEventRequest request = capturedDoorEvent();
        assertNotEquals(reported, request.timestamp(),
                "服务端未采用报文 timestamp（当前行为快照）");
        long drift = Math.abs(System.currentTimeMillis() - request.timestamp());
        assertTrue(drift < 60_000, "DTO timestamp 应为服务端接收时刻，实测偏差 " + drift + "ms");
    }

    // ----------------------------------------------------------- HEARTBEAT

    /** 模拟器心跳 → trade 的 7 参重载必须收到 deviceId/版本/温度；未上报的三项为 null。 */
    @Test
    void heartbeat_fromSimulatorPayload_reachesTradeWithVersionsAndTemp() throws Exception {
        Map<String, Object> payload = SimulatorSupport.heartbeatPayload(
                DEVICE_ID, 1_700_000_000_002L, "0.9.0", "1.0.0", 12);

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        verify(tradeServiceClient).notifyHeartbeat(
                eq(DEVICE_ID), eq("0.9.0"), eq("1.0.0"), eq(12),
                (Double) isNull(), (Double) isNull(), (Double) isNull());
    }

    // ----------------------------------------------------------------- ACK

    /** 模拟器 ACK → 命令追踪按 (commandId, success, topic 里的 deviceId) 记账（H55 防伪造来源）。 */
    @Test
    void ack_fromSimulatorPayload_recordsAckWithTopicDeviceId() throws Exception {
        Map<String, Object> payload = SimulatorSupport.ackPayload("cmd-contract-1", true, 1_700_000_000_003L);

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        verify(commandTracker).recordAck(eq("cmd-contract-1"), eq(true), eq(DEVICE_ID));
    }

    // --------------------------------------------------------------- ALERT

    /**
     * 边缘告警（{@code EDGE_QUEUE_ABANDON} 等）→ 转运营告警通道。
     *
     * <p>⚠️ 该报文由**真机端 Kotlin** 发出（{@code MqttDeviceClient.kt} 的 {@code publishAlert}），
     * 模拟器不产生，故此处字段名按真机端约定书写；其 {@code type="ALERT"} 取值与常量集合的
     * 一致性由门禁 {@code check:edge-cloud-mqtt-contract} 守护（Kotlin 侧改 payload.type 会红）。
     * {@code alertType} 等**报文体字段**不在门禁覆盖面内，改它们本类不会红。
     */
    @Test
    void alert_fromDeviceClientConvention_reachesOpsAlert() throws Exception {
        String payload = """
                {"type":"ALERT","alertType":"EDGE_QUEUE_ABANDON","message":"mqtt outbound abandoned","timestamp":1700000000004}
                """;

        listener.messageArrived(MqttTopics.event(DEVICE_ID), mqtt(payload));

        verify(tradeServiceClient).notifyOpsAlert(
                eq("EDGE_QUEUE_ABANDON"), eq("mqtt outbound abandoned"), eq(DEVICE_ID));
    }

    // ---------------------------------------------------------------- 主题

    /**
     * 主题契约：模拟器**发布**用的 {@link MqttTopics#event} 必须落在云端订阅的过滤式内；
     * 反之命令 topic 不得被事件订阅命中（否则下行指令会被当成上行事件解析）。
     */
    @Test
    void topicContract_publishedEventMatchesCloudSubscriptions_commandDoesNot() {
        String published = MqttTopics.event(DEVICE_ID);
        assertEquals("cabinet/" + DEVICE_ID + "/evt", published);

        assertTrue(matches(MqttTopics.ALL_EVENTS, published), "cabinet/+/evt 应匹配设备事件 topic");
        assertTrue(matches(MqttTopics.ALL_EVENTS_SHARED, published), "$share/aicabinet/cabinet/+/evt 应匹配");
        assertFalse(matches(MqttTopics.ALL_EVENTS, MqttTopics.command(DEVICE_ID)),
                "下行命令 topic 不得被上行事件订阅命中");
        assertFalse(matches(MqttTopics.ALL_EVENTS_SHARED, MqttTopics.command(DEVICE_ID)),
                "共享订阅同样不得命中下行命令 topic");
    }

    /** 设备侧订阅下行指令用的 topic 必须就是云端发布指令的 topic（同一函数产出，防一侧改规则）。 */
    @Test
    void topicContract_deviceSubscribeEqualsCommandTopic() {
        assertEquals(MqttTopics.command(DEVICE_ID), MqttTopics.deviceSubscribe(DEVICE_ID));
        assertEquals("cabinet/" + DEVICE_ID + "/cmd", MqttTopics.deviceSubscribe(DEVICE_ID));
    }

    // -------------------------------------------------------------- helpers

    private DoorEventRequest capturedDoorEvent() {
        ArgumentCaptor<DoorEventRequest> captor = ArgumentCaptor.forClass(DoorEventRequest.class);
        verify(tradeServiceClient).notifyDoorEvent(captor.capture());
        return captor.getValue();
    }

    private MqttMessage mqtt(Map<String, Object> payload) throws Exception {
        return new MqttMessage(objectMapper.writeValueAsBytes(payload));
    }

    private MqttMessage mqtt(String payload) {
        return new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * MQTT 主题过滤式匹配（`+` 单级通配、`#` 多级通配、`$share/{group}/` 前缀）。
     *
     * <p>刻意手写而不引入 broker 依赖：这里要断言的是**本仓约定的过滤式字符串**，
     * 用一个与我们无关的第三方实现反而说不清语义。
     */
    private static boolean matches(String filter, String topic) {
        String f = filter;
        if (f.startsWith("$share/")) {
            String[] share = f.split("/", 3);
            f = share.length >= 3 ? share[2] : f;
        }
        String[] fp = f.split("/");
        String[] tp = topic.split("/");
        for (int i = 0; i < fp.length; i++) {
            if ("#".equals(fp[i])) {
                return true;
            }
            if (i >= tp.length) {
                return false;
            }
            if (!"+".equals(fp[i]) && !fp[i].equals(tp[i])) {
                return false;
            }
        }
        return fp.length == tp.length;
    }

    /** 供编译器确认常量仍存在（改错类型/删除即编译失败，比字符串断言更早红）。 */
    @Test
    void eventTypeConstants_areTheOnesCloudDispatchesOn() {
        assertEquals("DOOR", CabinetConstants.MQTT_EVENT_TYPE_DOOR);
        assertEquals("HEARTBEAT", CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT);
        assertEquals("ACK", CabinetConstants.MQTT_EVENT_TYPE_ACK);
        // O4：本类上面就在测 ALERT 分支，常量却一直没断言 —— 补上（否则「测了 ALERT」
        // 与「常量里没有 ALERT」能同时成立，正是本次抓到的那类裂缝）。
        assertEquals("ALERT", CabinetConstants.MQTT_EVENT_TYPE_ALERT);
    }
}
