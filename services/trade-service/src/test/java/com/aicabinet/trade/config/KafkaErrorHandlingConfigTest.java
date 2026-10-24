package com.aicabinet.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Kafka 失败策略的挂载点：没有 {@link DefaultErrorHandler} 时，监听方法抛出的异常会走
 * 容器默认行为（有限次重试后**丢弃**），消息同样会静默消失；DLT 主题也必须显式注册。
 */
class KafkaErrorHandlingConfigTest {

    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaErrorHandlingConfig.class)
            .withBean("kafkaTemplate", KafkaTemplate.class, () -> kafkaTemplate);

    @Test
    void registersErrorHandlerAndDltTopicsWhenEnabled() {
        runner.withPropertyValues("aicabinet.notify.async-enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(DefaultErrorHandler.class);
            assertThat(ctx).hasBean("notificationDispatchRequestTopic");
            assertThat(ctx).hasBean("notificationDispatchRequestDltTopic");
        });
    }

    @Test
    void staysOffWhenFlagFalse() {
        runner.withPropertyValues("aicabinet.notify.async-enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(DefaultErrorHandler.class);
            assertThat(ctx).doesNotHaveBean("notificationDispatchRequestDltTopic");
        });
    }

    @Test
    void staysOffWhenFlagMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DefaultErrorHandler.class));
    }
}
