package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 通知外部渠道异步化：启用 aicabinet.notify.async-enabled 后，外呼走 Kafka 队列。 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class NotificationDispatchProducer {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ackTimeout;

    public NotificationDispatchProducer(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        @Value("${aicabinet.notify.kafka-ack-timeout:5s}") Duration ackTimeout) {
        if (ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("aicabinet.notify.kafka-ack-timeout 必须大于 0");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.ackTimeout = ackTimeout;
    }

    public void publish(NotificationDispatchMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    KafkaTopics.NOTIFY_DISPATCH_REQUEST, String.valueOf(message.userId()), json);
            future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("published notification dispatch userId={} template={}",
                    message.userId(), message.templateCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing notification dispatch", e);
        } catch (ExecutionException | TimeoutException | RuntimeException | JsonProcessingException e) {
            throw new IllegalStateException("failed to publish notification dispatch", e);
        }
    }
}
