package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.service.ExternalNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** 消费通知分发队列并执行微信订阅消息 / 短信外呼。 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.notify", name = "async-enabled", havingValue = "true")
public class NotificationDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchListener.class);

    /** M09：消费幂等键前缀，按 payload sha256 去重，24h 内重投/重放只处理一次。 */
    private static final String DEDUPE_KEY_PREFIX = "aicabinet:notify:dispatch:";
    private static final Duration DEDUPE_TTL = Duration.ofHours(24);

    private final ExternalNotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;

    public NotificationDispatchListener(ExternalNotificationDispatcher dispatcher,
                                        ObjectMapper objectMapper,
                                        RedissonClient redisson) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
    }

    @KafkaListener(topics = KafkaTopics.NOTIFY_DISPATCH_REQUEST, groupId = "trade-service")
    public void onMessage(String payload) {
        try {
            if (!tryMarkDispatched(payload)) {
                log.info("notification dispatch duplicated, skip sha256={}", sha256Hex(payload));
                return;
            }
            NotificationDispatchMessage msg = objectMapper.readValue(payload, NotificationDispatchMessage.class);
            dispatcher.dispatch(msg);
        } catch (Exception e) {
            log.error("failed to process notification dispatch payload={}", payload, e);
        }
    }

    /** setIfAbsent 抢占幂等键；已存在（24h 内处理过）返回 false。 */
    private boolean tryMarkDispatched(String payload) {
        RBucket<String> bucket = redisson.getBucket(DEDUPE_KEY_PREFIX + sha256Hex(payload), StringCodec.INSTANCE);
        return bucket.setIfAbsent("1", DEDUPE_TTL);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
