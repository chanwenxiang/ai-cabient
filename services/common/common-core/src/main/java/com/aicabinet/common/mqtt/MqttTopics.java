package com.aicabinet.common.mqtt;

/**
 * MQTT Topic 命名规范。
 * 参考旧系统 M8 Socket.IO 消息，统一为 MQTT 5.0。
 */
public final class MqttTopics {

    private MqttTopics() {}

    public static String command(String deviceId) {
        return "cabinet/" + deviceId + "/cmd";
    }

    public static String event(String deviceId) {
        return "cabinet/" + deviceId + "/evt";
    }

    public static String videoChunk(String deviceId) {
        return "cabinet/" + deviceId + "/data/video-chunk";
    }

    /** 设备订阅：接收下行指令 */
    public static String deviceSubscribe(String deviceId) {
        return command(deviceId);
    }

    /** 云端订阅：接收上行事件（可用通配符） */
    public static final String ALL_EVENTS = "cabinet/+/evt";
    public static final String ALL_EVENTS_SHARED = "$share/aicabinet/cabinet/+/evt";
    public static final String ALL_HEARTBEATS = "cabinet/+/evt/heartbeat";
}
