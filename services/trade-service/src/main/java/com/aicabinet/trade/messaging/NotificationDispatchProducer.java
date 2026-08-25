package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 通知外部渠道异步化：启用 aicabinet.notify.async-enabled 后，外呼走 Kafka 队列。 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class NotificationDispatchProducer {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public NotificationDispatchProducer(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(NotificationDispatchMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(KafkaTopics.NOTIFY_DISPATCH_REQUEST,
                    String.valueOf(message.userId()), json);
            log.info("published notification dispatch userId={} template={}",
                    message.userId(), message.templateCode());
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish notification dispatch", e);
        }
    }
}
