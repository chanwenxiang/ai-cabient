package com.aicabinet.trade.config;

import com.aicabinet.common.constants.KafkaTopics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费失败策略：有限重试 → 死信（DLT），禁止「catch 后正常返回」。
 *
 * <p>为什么必须有这一层（lessons #19/#75/#95）：Spring Kafka 在监听方法**正常返回**时
 * 照常提交偏移。若业务代码 catch 住异常只打日志，消息既不会被重投，也不会进 DLT，
 * 表现为「消费成功但通知没发出去」——静默丢失比抛错更难发现。
 *
 * <p>重试口径：首次 + 2 次重试（间隔 1s），仍失败则投递 {@code <topic>.DLT}。
 * DLT 发布开启 {@code failIfSendResultIsError}：发布本身失败会抛出、不提交偏移，
 * 避免「以为进了 DLT、其实消息已丢」。
 */
@Configuration
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        // 同步等待 DLT 发布结果：异步 send 的失败默认不会反馈给容器
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    @Bean
    public NewTopic notificationDispatchRequestTopic() {
        // replicas(-1) = 交给 broker 默认副本数，避免把单机副本数写死带进生产
        return TopicBuilder.name(KafkaTopics.NOTIFY_DISPATCH_REQUEST)
                .partitions(1)
                .replicas(-1)
                .build();
    }

    @Bean
    public NewTopic notificationDispatchRequestDltTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFY_DISPATCH_REQUEST_DLT)
                .partitions(1)
                .replicas(-1)
                .build();
    }
}
