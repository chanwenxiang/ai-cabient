package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.mqtt.MqttTopics;
import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.config.MqttProperties;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import com.aicabinet.device.service.DeviceCommandTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttEventListenerDoorTest {

    @Mock TradeServiceClient tradeServiceClient;
    @Mock MqttConnectOptionsFactory connectOptionsFactory;
    @Mock MqttConnectionRegistry connectionRegistry;
    @Mock DeviceCommandTracker commandTracker;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry meterRegistry;
    private MqttEventListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(new DoorEventDeduplicator(), meterRegistry);
    }

    private MqttEventListener newListener(DoorEventDeduplicator deduplicator, SimpleMeterRegistry registry) {
        return new MqttEventListener(
                new ObjectMapper(),
                tradeServiceClient,
                new MqttProperties("tcp://localhost:1883", "test", null, null, null, null, "target/mqtt-test"),
                connectOptionsFactory,
                connectionRegistry,
                new DeviceMqttMetrics(registry),
                deduplicator,
                commandTracker);
    }

    private DoorEventDeduplicator sharedRedisDeduplicator() {
        Map<String, String> store = new ConcurrentHashMap<>();
        when(redis.hasKey(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(redis.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> {
            store.put(inv.getArgument(0), "1");
            return null;
        }).when(valueOps).set(anyString(), eq("1"), any(Duration.class));
        return new DoorEventDeduplicator(redis);
    }

    /** Q2: 同 session CLOSED 同 fingerprint 重放 → 仅转发一次。 */
    @Test
    void closedReplay_sameFingerprint_forwardsOnce() throws Exception {
        String payload = """
                {"type":"%s","sessionId":"S-Q2","deviceId":"CAB-001","doorState":"CLOSED"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        listener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        listener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(1)).notifyDoorEvent(any(DoorEventRequest.class));
        assertEquals(1.0, counter(meterRegistry, "device.mqtt.door", "forwarded"));
        assertEquals(1.0, counter(meterRegistry, "device.mqtt.door", "deduped"));
    }

    /** Q4/M11: trade 转发失败不写幂等键，重投可再次转发。 */
    @Test
    void tradeFailure_notMarked_allowsRetryForward() throws Exception {
        String payload = """
                {"type":"%s","sessionId":"S-Q4","deviceId":"CAB-001","doorState":"CLOSED","videoUri":"v1"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        doThrow(new RuntimeException("trade 500"))
                .doNothing()
                .when(tradeServiceClient).notifyDoorEvent(any(DoorEventRequest.class));

        listener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        listener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(2)).notifyDoorEvent(any(DoorEventRequest.class));
        assertEquals(1.0, counter(meterRegistry, "device.trade.forward", "failure"));
        assertEquals(1.0, counter(meterRegistry, "device.mqtt.door", "forwarded"));
    }

    /** Q1: topic/body deviceId 不一致 → 不调 trade。 */
    @Test
    void deviceIdMismatch_skipsTrade() throws Exception {
        String payload = """
                {"type":"%s","sessionId":"S-Q1","deviceId":"CAB-OTHER","doorState":"CLOSED"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        listener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(0)).notifyDoorEvent(any(DoorEventRequest.class));
    }

    /** Q3: 不同 eventSeq 的 CLOSED 均可转发（同 session）。 */
    @Test
    void closedDifferentEventSeq_forwardsTwice() throws Exception {
        String first = """
                {"type":"%s","sessionId":"S-Q3","deviceId":"CAB-001","doorState":"CLOSED","eventSeq":"1","videoUri":"v1"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);
        String second = """
                {"type":"%s","sessionId":"S-Q3","deviceId":"CAB-001","doorState":"CLOSED","eventSeq":"2","videoUri":"v2"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        listener.messageArrived("cabinet/CAB-001/evt", mqtt(first));
        listener.messageArrived("cabinet/CAB-001/evt", mqtt(second));

        verify(tradeServiceClient, times(2)).notifyDoorEvent(any(DoorEventRequest.class));
        assertEquals(2.0, counter(meterRegistry, "device.mqtt.door", "forwarded"));
        assertEquals(0.0, counter(meterRegistry, "device.mqtt.door", "deduped"));
    }

    /**
     * Q7：共享订阅多实例安全网 — 两个 listener 共用 Redis SET NX 去重器，
     * 同事件只转发一次（不证明 broker 只投递一次，证明第二实例会被 Redis 拦住）。
     */
    @Test
    void q7_sharedRedisDedup_acrossTwoListeners_forwardsOnce() throws Exception {
        DoorEventDeduplicator shared = sharedRedisDeduplicator();
        SimpleMeterRegistry m1 = new SimpleMeterRegistry();
        SimpleMeterRegistry m2 = new SimpleMeterRegistry();
        MqttEventListener instanceA = newListener(shared, m1);
        MqttEventListener instanceB = newListener(shared, m2);

        String payload = """
                {"type":"%s","sessionId":"S-Q7","deviceId":"CAB-001","doorState":"CLOSED","eventSeq":"1"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        instanceA.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        instanceB.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(1)).notifyDoorEvent(any(DoorEventRequest.class));
        assertEquals(1.0, counter(m1, "device.mqtt.door", "forwarded") + counter(m2, "device.mqtt.door", "forwarded"));
        assertEquals(1.0, counter(m1, "device.mqtt.door", "deduped") + counter(m2, "device.mqtt.door", "deduped"));
        assertTrue(MqttTopics.ALL_EVENTS_SHARED.startsWith("$share/aicabinet/"));
    }

    /** Q7 附：投递 topic 偶发带 $share 前缀时仍能解析 deviceId 并转发。 */
    @Test
    void q7_sharePrefixedTopic_extractsDeviceId_andForwards() throws Exception {
        String payload = """
                {"type":"%s","sessionId":"S-Q7-SHARE","deviceId":"CAB-001","doorState":"CLOSED","eventSeq":"9"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        listener.messageArrived("$share/aicabinet/cabinet/CAB-001/evt", mqtt(payload));

        ArgumentCaptor<DoorEventRequest> captor = ArgumentCaptor.forClass(DoorEventRequest.class);
        verify(tradeServiceClient, times(1)).notifyDoorEvent(captor.capture());
        assertEquals("CAB-001", captor.getValue().deviceId());
        assertEquals("S-Q7-SHARE", captor.getValue().sessionId());
    }

    private static double counter(SimpleMeterRegistry registry, String name, String result) {
        var c = registry.find(name).tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private static MqttMessage mqtt(String json) {
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }

    /** C11：转发成功必须显式手动 ACK。 */
    @Test
    void c11_doorForwardSuccess_acksMessage() {
        MqttEventListener spyListener = spy(listener);
        String payload = """
                {"type":"%s","sessionId":"S-C11","deviceId":"CAB-001","doorState":"CLOSED"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(1)).notifyDoorEvent(any(DoorEventRequest.class));
        verify(spyListener, times(1)).acknowledgeMessage(anyString(), any(MqttMessage.class));
    }

    /** C11/M11：去重命中的重复消息直接 ACK 丢弃。 */
    @Test
    void c11_duplicateDoorEvent_ackedAndDropped() {
        MqttEventListener spyListener = spy(listener);
        String payload = """
                {"type":"%s","sessionId":"S-C11-DUP","deviceId":"CAB-001","doorState":"CLOSED"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient, times(1)).notifyDoorEvent(any(DoorEventRequest.class));
        verify(spyListener, times(2)).acknowledgeMessage(anyString(), any(MqttMessage.class));
    }

    /** C11：转发失败不 ACK（等 broker 重投）；同一消息连续失败 3 次后 ACK 丢弃防重投风暴。 */
    @Test
    void c11_doorForwardFailure_notAcked_thirdFailureAckDropped() {
        MqttEventListener spyListener = spy(listener);
        String payload = """
                {"type":"%s","sessionId":"S-C11-FAIL","deviceId":"CAB-001","doorState":"CLOSED"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR);
        doThrow(new RuntimeException("trade down"))
                .when(tradeServiceClient).notifyDoorEvent(any(DoorEventRequest.class));

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        verify(spyListener, never()).acknowledgeMessage(anyString(), any(MqttMessage.class));
        verify(tradeServiceClient, times(2)).notifyDoorEvent(any(DoorEventRequest.class));

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));
        verify(spyListener, times(1)).acknowledgeMessage(anyString(), any(MqttMessage.class));
        verify(tradeServiceClient, times(3)).notifyDoorEvent(any(DoorEventRequest.class));
    }

    /** C11：不可解析报文重投也无法恢复，直接 ACK 丢弃。 */
    @Test
    void c11_malformedPayload_ackedAndDropped() {
        MqttEventListener spyListener = spy(listener);

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt("not-json{"));

        verify(spyListener, times(1)).acknowledgeMessage(anyString(), any(MqttMessage.class));
        verify(tradeServiceClient, never()).notifyDoorEvent(any(DoorEventRequest.class));
    }

    /** C11：未知类型消息忽略并 ACK，避免无限重投。 */
    @Test
    void c11_unknownType_ignoredAndAcked() {
        MqttEventListener spyListener = spy(listener);

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt("{\"type\":\"MYSTERY\"}"));

        verify(tradeServiceClient, never()).notifyDoorEvent(any(DoorEventRequest.class));
        verify(spyListener, times(1)).acknowledgeMessage(anyString(), any(MqttMessage.class));
    }

    /** H55：ACK 携带 topic，tracker 收到从 topic 解析出的 deviceId。 */
    @Test
    void h55_ackPayload_passesTopicDeviceIdToTracker() {
        String payload = """
                {"type":"ACK","commandId":"cmd-9","success":true}
                """;

        listener.messageArrived("cabinet/CAB-007/evt", mqtt(payload));

        verify(commandTracker).recordAck("cmd-9", true, "CAB-007");
    }

    /** H62a：edge 告警事件转发到 trade 运营告警通道，成功后 ACK。 */
    @Test
    void h62a_edgeAlert_forwardedToTradeAndAcked() {
        MqttEventListener spyListener = spy(listener);
        String payload = """
                {"type":"ALERT","alertType":"EDGE_QUEUE_ABANDON","message":"upload queue abandoned"}
                """;

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(tradeServiceClient).notifyOpsAlert("EDGE_QUEUE_ABANDON", "upload queue abandoned", "CAB-001");
        verify(spyListener, times(1)).acknowledgeMessage(anyString(), any(MqttMessage.class));
    }

    /** H62a：告警转发失败不 ACK，走 C11 重投语义（body deviceId 优先于 topic）。 */
    @Test
    void h62a_alertForwardFailure_notAcked() {
        MqttEventListener spyListener = spy(listener);
        String payload = """
                {"type":"ALERT","alertType":"EDGE_QUEUE_ABANDON","message":"boom","deviceId":"CAB-001"}
                """;
        doThrow(new RuntimeException("trade down"))
                .when(tradeServiceClient).notifyOpsAlert(anyString(), anyString(), anyString());

        spyListener.messageArrived("cabinet/CAB-001/evt", mqtt(payload));

        verify(spyListener, never()).acknowledgeMessage(anyString(), any(MqttMessage.class));
    }
}
