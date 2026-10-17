package com.aicabinet.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @EnableKafka} 的挂载点：它决定盘上【所有】{@code @KafkaListener} 能否生效，
 * 挂错开关会让通知异步分发静默失效（容器不建、消息不消费、没有任何报错）。
 */
class KafkaEnableConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaEnableConfig.class);

    @Test
    void registersListenerContainersOnlyWhenNotifyAsyncEnabled() {
        runner.withPropertyValues("aicabinet.notify.async-enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(KafkaListenerEndpointRegistry.class));
    }

    @Test
    void staysOffWhenFlagFalse() {
        runner.withPropertyValues("aicabinet.notify.async-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaListenerEndpointRegistry.class));
    }

    @Test
    void staysOffWhenFlagMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaListenerEndpointRegistry.class));
    }
}
