package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.service.ExternalNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 消费通知分发队列并执行微信订阅消息 / 短信外呼。 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class NotificationDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchListener.class);

    private final ExternalNotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public NotificationDispatchListener(ExternalNotificationDispatcher dispatcher,
                                        ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.NOTIFY_DISPATCH_REQUEST, groupId = "trade-service")
    public void onMessage(String payload) {
        try {
            NotificationDispatchMessage msg = objectMapper.readValue(payload, NotificationDispatchMessage.class);
            dispatcher.dispatch(msg);
        } catch (Exception e) {
            log.error("failed to process notification dispatch payload={}", payload, e);
        }
    }
}
