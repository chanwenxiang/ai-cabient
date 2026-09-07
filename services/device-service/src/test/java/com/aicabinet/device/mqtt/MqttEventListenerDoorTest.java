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
import static org.mockito.Mockito.doThrow;
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
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return store.putIfAbsent(key, "1") == null;
        });
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

    /** Q4: trade 转发失败清除去重键，重投可再次转发。 */
    @Test
    void tradeFailure_clearsDedup_allowsRetryForward() throws Exception {
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
}
