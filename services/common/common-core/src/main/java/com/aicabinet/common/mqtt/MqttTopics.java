package com.aicabinet.common.mqtt;

/**
 * MQTT Topic 命名规范。
 * 参考旧系统 M8 Socket.IO 消息，统一为 MQTT 5.0。
 */
public final class MqttTopics {
    private static final String CABINET = "cabinet/";


    private MqttTopics() {}

    public static String command(String deviceId) {
        return CABINET + deviceId + "/cmd";
    }

    public static String event(String deviceId) {
        return CABINET + deviceId + "/evt";
    }

    /** 设备订阅：接收下行指令 */
    public static String deviceSubscribe(String deviceId) {
        return command(deviceId);
    }

    /** 云端订阅：接收上行事件（可用通配符） */
    public static final String ALL_EVENTS = "cabinet/+/evt";
    public static final String ALL_EVENTS_SHARED = "$share/aicabinet/cabinet/+/evt";

    // O4（2026-09-19）移除两个**死声明**，勿凭记忆加回：
    //
    // · `videoChunk(deviceId)` = "cabinet/{id}/data/video-chunk"
    //   全仓零调用（生产 + 测试），且视频分片从未实现过 MQTT 通道；
    //   如需引入，请同时落地发布方、订阅方与 check:edge-cloud-mqtt-contract 的对账。
    //
    // · `ALL_HEARTBEATS` = "cabinet/+/evt/heartbeat"
    //   值本身**就是错的**：edge 的 `MqttDeviceClient.publishHeartbeat()` 发往
    //   `cabinet/{deviceId}/evt`，心跳靠 **payload.type=HEARTBEAT** 区分，而非独立子 topic
    //   ⇒ 该 filter 永远匹配不到任何报文。心跳若需按类型过滤，只能在订阅端按 payload 判，
    //   不能用 topic filter 表达。
}
