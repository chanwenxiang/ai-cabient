package com.aicabinet.device.mqtt;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DoorEventRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * R20-10 后半判据：Paho 回调线程不得执行任何出站 trade HTTP。
 *
 * <p>既有 {@link MqttEventListenerDoorTest} 注入 direct executor 钉的是「语义」
 * （成功才 ACK、失败不 ACK、去重、限流）；本类注入真实/可控 executor 钉的是「线程边界」
 * 与生产池配置。两者互补——只跑前者的话，把实现改回同步转发照样全绿。</p>
 */
@ExtendWith(MockitoExtension.class)
class MqttEventListenerAsyncForwardTest {

    private static final String TOPIC = "cabinet/CAB-001/evt";

    @Mock TradeServiceClient tradeServiceClient;
    @Mock MqttConnectOptionsFactory connectOptionsFactory;
    @Mock MqttConnectionRegistry connectionRegistry;
    @Mock DeviceCommandTracker commandTracker;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private MqttEventListener newListener(Executor forwardExecutor) {
        return newListener(forwardExecutor, new DeviceMqttMetrics(meterRegistry));
    }

    private MqttEventListener newListener(Executor forwardExecutor, DeviceMqttMetrics metrics) {
        return new MqttEventListener(
                new ObjectMapper(),
                tradeServiceClient,
                new MqttProperties("tcp://localhost:1883", "test", null, null, null, null, "target/mqtt-test"),
                connectOptionsFactory,
                connectionRegistry,
                metrics,
                new DoorEventDeduplicator(),
                commandTracker,
                forwardExecutor);
    }

    /**
     * 核心负向判据：把 HTTP 卡住不放，回调线程仍必须已经返回。
     * 若实现退回「回调线程内同步 notifyDoorEvent」（或注入 Runnable::run），
     * callback.get(1s) 必然超时 ⇒ 判据能红。
     */
    @Test
    void r20_10_callbackThreadReturnsWhileDoorHttpStillBlocked() throws Exception {
        CountDownLatch httpEntered = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        AtomicReference<String> httpThread = new AtomicReference<>();
        doAnswer(inv -> {
            httpThread.set(Thread.currentThread().getName());
            httpEntered.countDown();
            releaseHttp.await(3, TimeUnit.SECONDS);
            return null;
        }).when(tradeServiceClient).notifyDoorEvent(any(DoorEventRequest.class));

        ExecutorService worker = Executors.newSingleThreadExecutor(named("mqtt-forward-test"));
        ExecutorService caller = Executors.newSingleThreadExecutor(named("paho-callback-test"));
        try {
            MqttEventListener listener = newListener(worker);
            Future<?> callback = caller.submit(
                    () -> listener.messageArrived(TOPIC, mqtt(doorPayload("S-ASYNC-BLOCK"))));

            assertTrue(httpEntered.await(2, TimeUnit.SECONDS), "转发任务应在工作线程启动");
            assertDoesNotThrow(() -> callback.get(1, TimeUnit.SECONDS),
                    "Paho 回调线程不得等待出站 HTTP 完成（R20-10）");
            assertEquals("mqtt-forward-test", httpThread.get(), "HTTP 必须跑在转发专用线程");
        } finally {
            releaseHttp.countDown();
            caller.shutdownNow();
            worker.shutdownNow();
        }
    }

    /** C11 语义不因换线程而变：ACK 严格排在 HTTP 成功之后。 */
    @Test
    void r20_10_ackOnlyAfterSuccessfulDoorForward() {
        CountDownLatch httpDone = new CountDownLatch(1);
        doAnswer(inv -> {
            httpDone.countDown();
            return null;
        }).when(tradeServiceClient).notifyDoorEvent(any(DoorEventRequest.class));

        ExecutorService worker = Executors.newSingleThreadExecutor(named("mqtt-forward-ack"));
        try {
            MqttEventListener listener = spy(newListener(worker));
            listener.messageArrived(TOPIC, mqtt(doorPayload("S-ASYNC-ACK")));

            verify(listener, timeout(2000)).acknowledgeMessage(anyString(), any(MqttMessage.class));
            assertEquals(0, httpDone.getCount(), "ACK 时 HTTP 必须已完成，不能先 ACK 后转发");
            assertEquals(1.0, counter(meterRegistry, "device.mqtt.door", "forwarded"));
        } finally {
            worker.shutdownNow();
        }
    }

    /** C11/M11：转发失败不 ACK、不写幂等键，交 broker 重投。 */
    @Test
    void r20_10_forwardFailureLeavesMessageUnacked() {
        doThrow(new RuntimeException("trade down"))
                .when(tradeServiceClient).notifyDoorEvent(any(DoorEventRequest.class));

        ExecutorService worker = Executors.newSingleThreadExecutor(named("mqtt-forward-fail"));
        try {
            // 显式等工作线程进 catch，再断言「不 ACK」——否则是判据自己和工作线程竞态。
            DeviceMqttMetrics metrics = spy(new DeviceMqttMetrics(meterRegistry));
            MqttEventListener listener = spy(newListener(worker, metrics));
            listener.messageArrived(TOPIC, mqtt(doorPayload("S-ASYNC-FAIL")));

            verify(metrics, timeout(2000)).recordTradeFailure();
            verify(listener, never()).acknowledgeMessage(anyString(), any(MqttMessage.class));
            assertEquals(1.0, counter(meterRegistry, "device.trade.forward", "failure"));
            assertEquals(0.0, counter(meterRegistry, "device.mqtt.door", "forwarded"));
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * 队列满＝背压：不 ACK、不调 trade、记 rejected 指标，且**不计入 deliveryFailures**
     * ——连续 3 次被拒也不该触发 C11 的「3 次失败 ACK 丢弃」把消息丢掉。
     */
    @Test
    void r20_10_queueFullLeavesUnackedWithoutFailureCounting() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        MqttEventListener listener = spy(newListener(rejecting));

        for (int i = 0; i < 3; i++) {
            listener.messageArrived(TOPIC, mqtt(doorPayload("S-ASYNC-QUEUE-" + i)));
        }

        verify(tradeServiceClient, never()).notifyDoorEvent(any(DoorEventRequest.class));
        verify(listener, never()).acknowledgeMessage(anyString(), any(MqttMessage.class));
        assertEquals(3.0, counter(meterRegistry, "device.mqtt.forward", "rejected"));
        assertEquals(0.0, counter(meterRegistry, "device.trade.forward", "failure"));
    }

    /** 同族覆盖：门磁 / 心跳 / 告警三类出站 HTTP 都必须走专用 executor（铁律 75）。 */
    @Test
    void r20_10_doorHeartbeatAlertAllSubmitToForwardExecutor() {
        AtomicInteger submitted = new AtomicInteger();
        Executor counting = task -> submitted.incrementAndGet();

        MqttEventListener listener = newListener(counting);
        listener.messageArrived(TOPIC, mqtt(doorPayload("S-ASYNC-ALL")));
        listener.messageArrived(TOPIC, mqtt(heartbeatPayload()));
        listener.messageArrived(TOPIC, mqtt(alertPayload()));

        assertEquals(3, submitted.get(), "三类转发都必须提交到专用 executor，不能在回调线程直调");
        verifyNoInteractions(tradeServiceClient);
    }

    /**
     * 生产池硬约束：有界队列 + AbortPolicy。测试用 direct executor 时上面几条照样能绿，
     * 所以必须单独钉住生产构造器建出来的那个池（防「只钉一侧」）。
     */
    @Test
    void r20_10_defaultForwardExecutorIsBoundedAndAborts() {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) MqttEventListener.defaultForwardExecutor();
        try {
            assertEquals(8, pool.getCorePoolSize());
            assertEquals(8, pool.getMaximumPoolSize());
            assertTrue(pool.getQueue() instanceof ArrayBlockingQueue, "队列必须有界");
            assertEquals(256, pool.getQueue().remainingCapacity());
            assertTrue(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy,
                    "CallerRunsPolicy 会把 HTTP 压回 Paho 回调线程，等于没修 R20-10");
        } finally {
            pool.shutdownNow();
        }
    }

    private static String doorPayload(String sessionId) {
        return """
                {"type":"%s","sessionId":"%s","deviceId":"CAB-001","doorState":"CLOSED","eventSeq":"%s"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_DOOR, sessionId, sessionId);
    }

    private static String heartbeatPayload() {
        return """
                {"type":"%s","deviceId":"CAB-001","appVersion":"1.0.0"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_HEARTBEAT);
    }

    private static String alertPayload() {
        return """
                {"type":"%s","deviceId":"CAB-001","alertType":"EDGE_QUEUE_ABANDON","message":"upload queue abandoned"}
                """.formatted(CabinetConstants.MQTT_EVENT_TYPE_ALERT);
    }

    private static MqttMessage mqtt(String json) {
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static double counter(SimpleMeterRegistry registry, String name, String result) {
        var c = registry.find(name).tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }
}
