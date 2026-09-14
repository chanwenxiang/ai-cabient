package com.aicabinet.common.constants;

public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String VISION_RECOGNIZE_REQUEST = "aicabinet.vision.recognize.request";
    public static final String VISION_RECOGNIZE_RESULT = "aicabinet.vision.recognize.result";
    /** trade 消费识别结果失败后写入，避免无限重试打爆 listener */
    public static final String VISION_RECOGNIZE_RESULT_DLT = "aicabinet.vision.recognize.result.DLT";
    /** vision worker 消费识别请求失败后写入（与 KAFKA_VISION_REQUEST_DLT 默认名对齐） */
    public static final String VISION_RECOGNIZE_REQUEST_DLT = "aicabinet.vision.recognize.request.DLT";
    public static final String NOTIFY_DISPATCH_REQUEST = "aicabinet.notify.dispatch.request";
}
