package com.aicabinet.device.mqtt;

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
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * P0-7：**模拟器 → 真 EMQX → 云端订阅 → 真解析器 → trade** 的端到端集成测。
 *
 * <p>与两条既有资产的分工：
 * <ul>
 *   <li>{@link EdgeCloudMqttContractTest}（surefire，无 broker）证「字段/主题约定」；</li>
 *   <li>{@link EmqxSharedSubscriptionIT} 证「共享订阅在 broker 层只投一次」；</li>
 *   <li>本类证「**整条链路真的通**」—— 报文字段与 topic 都用模拟器的真实构造，
 *       broker 是容器里真的 EMQX，消费方是**真的 {@code MqttEventListener} 实例**
 *       （不是测试自写的订阅代码），出站打到 mock 的 trade 客户端上。</li>
 * </ul>
 *
 * <p>为什么不是只跑裸客户端订阅：那样测的是「测试自己的订阅写对了」，与本仓纪律里
 * 「判据要落在真正起作用的那几句」相反。这里连的是**生产订阅路径**
 * （{@code connect()} 内部 {@code subscribe(MqttTopics.ALL_EVENTS_SHARED, 1)}）。
 */
@Testcontainers(disabledWithoutDocker = true)
class EdgeCloudMqttIntegrationIT {

    private static final String DEVICE_ID = "CAB-P07-IT";

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static GenericContainer<?> emqx = new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.5"))
            .withExposedPorts(1883)
            .withEnv("EMQX_ALLOW_ANONYMOUS", "true")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    /**
     * 模拟器两类上行报文（关门事件 + 心跳）走完整链路后，trade 侧必须逐项还原。
     *
     * <p>⚠️ **刻意合并成一个用例、共用一个监听器**，原因是生产语义而非图省事：
     * {@code MqttConnectOptionsFactory} 用 {@code cleanSession=false}（QoS1 未 ACK 的消息要能在
     * 重连后重投，见该类注释），于是**客户端断开后它的共享订阅会话仍留在 broker 的 share group 里**。
     * 若每个场景各起一个监听器，第二个场景的消息会按轮询被投给「已断开但会话仍在」的前一个成员
     * （进它的离线队列、无人消费）⇒ 表现为**消息丢失、用例随机红绿**（实测：心跳单独成例时 0 次交互）。
     *
     * <p>🔴 这条同时是对生产的提醒：**副本滚动重启期间，共享订阅组内的残留会话会吞掉事件**。
     */
    @Test
    @Timeout(150)
    void simulatorDoorEventAndHeartbeat_travelThroughRealBroker_intoTrade() throws Exception {
        TradeServiceClient trade = mock(TradeServiceClient.class);
        MqttEventListener listener = connectedListener(trade);
        try {
            Map<String, Object> door = SimulatorSupport.doorEventPayload(
                    "S-P07-IT", DoorState.CLOSED.name(), System.currentTimeMillis(),
                    "minio://bucket/it.mp4", "UPLOADED", "[{\"camera\":\"TOP\"}]", "SINGLE", null);
            publishLikeSimulator(door);

            ArgumentCaptor<DoorEventRequest> captor = ArgumentCaptor.forClass(DoorEventRequest.class);
            verify(trade, timeout(20_000)).notifyDoorEvent(captor.capture());
            DoorEventRequest request = captor.getValue();
            assertEquals("S-P07-IT", request.sessionId());
            assertEquals(DEVICE_ID, request.deviceId(), "deviceId 须由 topic 还原（模拟器不发该字段）");
            assertEquals(DoorState.CLOSED, request.doorState());
            assertEquals("minio://bucket/it.mp4", request.videoUri());
            assertEquals("UPLOADED", request.uploadStatus());
            assertEquals("[{\"camera\":\"TOP\"}]", request.videoClipsJson());
            assertEquals("SINGLE", request.cameraFusionMode());

            Map<String, Object> heartbeat = SimulatorSupport.heartbeatPayload(
                    DEVICE_ID, System.currentTimeMillis(), "0.9.0", "1.0.0", 12);
            publishLikeSimulator(heartbeat);

            verify(trade, timeout(20_000)).notifyHeartbeat(
                    eq(DEVICE_ID), eq("0.9.0"), eq("1.0.0"), eq(12),
                    (Double) isNull(), (Double) isNull(), (Double) isNull());
        } finally {
            listener.disconnect();
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * 起一个**真的** {@code MqttEventListener}（连的容器 broker、走生产订阅路径）。
     *
     * <p>凭据留空 ⇒ {@link MqttConnectOptionsFactory} 走匿名分支，与容器
     * {@code EMQX_ALLOW_ANONYMOUS=true} 对应。
     */
    private MqttEventListener connectedListener(TradeServiceClient trade) throws Exception {
        String broker = "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883);
        MqttProperties props = new MqttProperties(
                broker, "p07-it-" + UUID.randomUUID().toString().substring(0, 8),
                null, null, null, null, "target/mqtt-it");
        MqttEventListener listener = new MqttEventListener(
                new ObjectMapper(),
                trade,
                props,
                new MqttConnectOptionsFactory(props),
                new MqttConnectionRegistry(),
                new DeviceMqttMetrics(new SimpleMeterRegistry()),
                new DoorEventDeduplicator(),
                mock(DeviceCommandTracker.class));
        listener.connect();
        // 共享订阅在 broker 侧的路由注册需要一点时间（既有 IT 同此处理）
        Thread.sleep(1000);
        return listener;
    }

    /** 以「设备」身份发布：topic 取生产用的 {@link MqttTopics#event}，报文取模拟器真实构造。 */
    private void publishLikeSimulator(Map<String, Object> payload) throws Exception {
        String broker = "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883);
        MqttClient device = new MqttClient(broker, "p07-device-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            device.connect(options);
            MqttMessage message = new MqttMessage(new ObjectMapper().writeValueAsBytes(payload));
            message.setQos(1);
            device.publish(MqttTopics.event(DEVICE_ID), message);
            System.out.println("[p07-it] published type=" + payload.get("type")
                    + " to " + MqttTopics.event(DEVICE_ID));
        } finally {
            if (device.isConnected()) {
                device.disconnect();
            }
            device.close();
        }
        // 消费是异步回调，断言交给 verify(timeout)
    }
}
