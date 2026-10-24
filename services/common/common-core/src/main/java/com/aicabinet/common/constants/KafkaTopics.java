package com.aicabinet.common.constants;

public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String NOTIFY_DISPATCH_REQUEST = "aicabinet.notify.dispatch.request";

    /**
     * 通知分发死信主题。
     *
     * <p>后缀必须与 {@code DeadLetterPublishingRecoverer} 的默认解析规则一致（{@code <topic>.DLT}），
     * 否则失败消息会被投到一个没人声明、也没人订阅的主题上（Kafka 自动建主题时更隐蔽）。
     */
    public static final String NOTIFY_DISPATCH_REQUEST_DLT = NOTIFY_DISPATCH_REQUEST + ".DLT";
}
