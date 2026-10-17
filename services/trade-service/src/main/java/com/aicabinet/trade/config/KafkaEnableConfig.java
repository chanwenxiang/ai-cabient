package com.aicabinet.trade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Kafka 监听容器开关。
 *
 * <p>{@code @EnableKafka} 只注册在本类上 ⇒ 它挂在哪个开关，就决定了【所有】{@code @KafkaListener}
 * 能否生效。原先挂在已退役的 {@code aicabinet.vision-async.enabled}（默认 false）上，
 * 那会让唯一在用的 {@code NotificationDispatchListener} 在 {@code aicabinet.notify.async-enabled=true}
 * 时依旧不消费——静默失效。现改为跟随通知异步开关。
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class KafkaEnableConfig {}
