package com.aicabinet.device.mqtt;

import com.aicabinet.common.mqtt.MqttTopics;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q7 broker 层：EMQX 共享订阅 {@code $share/aicabinet/...} 对同组多订阅者只投递一次。
 * <p>
 * 与 {@link MqttEventListenerDoorTest#q7_sharedRedisDedup_acrossTwoListeners_forwardsOnce} 互补——
 * 那边证 Redis SET NX；这里证 broker 本身不会 fan-out 给同 share group 的两个实例。
 */
@Testcontainers(disabledWithoutDocker = true)
class EmqxSharedSubscriptionIT {

    private static final String DEVICE_ID = "CAB-Q7-BROKER";
    private static final String PAYLOAD = "{\"type\":\"DOOR\",\"sessionId\":\"S-Q7-BROKER\",\"deviceId\":\""
            + DEVICE_ID + "\",\"doorState\":\"CLOSED\",\"eventSeq\":\"1\"}";

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static GenericContainer<?> emqx = new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.5"))
            .withExposedPorts(1883)
            .withEnv("EMQX_ALLOW_ANONYMOUS", "true")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    @Timeout(90)
    void q7_sharedSubscription_twoClients_onlyOneReceives() throws Exception {
        String broker = "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivery = new CountDownLatch(1);

        MqttClient subA = connectSubscriber(broker, "q7-sub-a-", received, delivery);
        MqttClient subB = connectSubscriber(broker, "q7-sub-b-", received, delivery);
        try {
            // 给 broker 一点时间完成共享组路由注册
            Thread.sleep(500);

            try (MqttClient publisher = new MqttClient(broker, "q7-pub-" + UUID.randomUUID(), new MemoryPersistence())) {
                publisher.connect(anonymousOptions());
                publisher.publish(MqttTopics.event(DEVICE_ID), new MqttMessage(PAYLOAD.getBytes(StandardCharsets.UTF_8)));
                publisher.disconnect();
            }

            assertTrue(delivery.await(15, TimeUnit.SECONDS), "shared group should deliver once");
            // 再等短暂窗口，确认第二订阅者不会再收到
            Thread.sleep(1500);
            assertEquals(1, received.size(), "EMQX $share group must deliver to exactly one member");
            assertTrue(received.get(0).contains("S-Q7-BROKER"));
        } finally {
            safeClose(subA);
            safeClose(subB);
        }
    }

    private static MqttClient connectSubscriber(
            String broker,
            String clientIdPrefix,
            List<String> received,
            CountDownLatch delivery) throws Exception {
        MqttClient client = new MqttClient(broker, clientIdPrefix + UUID.randomUUID(), new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // ignore for IT
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.add(new String(message.getPayload(), StandardCharsets.UTF_8));
                delivery.countDown();
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // publisher only
            }
        });
        client.connect(anonymousOptions());
        client.subscribe(MqttTopics.ALL_EVENTS_SHARED, 1);
        return client;
    }

    private static MqttConnectOptions anonymousOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        return options;
    }

    private static void safeClose(MqttClient client) {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
