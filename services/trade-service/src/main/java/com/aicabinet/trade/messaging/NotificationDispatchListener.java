package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.service.ExternalNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
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

    private static final String DEDUPE_KEY_PREFIX = "aicabinet:notify:dispatch:";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(5);
    private static final Duration DONE_TTL = Duration.ofHours(24);

    /** 仅 DONE 视为重复；PROCESSING 未过期返回 2 让 Kafka 重试，崩溃后 TTL 到期可重新抢占。旧版字面 1 允许重抢，由渠道 SENT 日志兜底防双发。 */
    private static final String CLAIM_LUA =
            "local value = redis.call('GET', KEYS[1]) "
            + "if not value then "
            + "  redis.call('SET', KEYS[1], ARGV[1] .. '|PROCESSING', 'PX', ARGV[2]) return 1 end "
            + "if value == '1' then "
            + "  redis.call('SET', KEYS[1], ARGV[1] .. '|PROCESSING', 'PX', ARGV[2]) return 1 end "
            + "if string.sub(value, -5) == '|DONE' then return 0 end "
            + "return 2";

    /** owner 校验后原子转为 DONE 并延长 TTL，避免 CAS 与过期设置之间崩溃。 */
    private static final String MARK_DONE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] .. '|PROCESSING' then "
            + "  redis.call('SET', KEYS[1], ARGV[1] .. '|DONE', 'PX', ARGV[2]) return 1 end "
            + "return 0";

    /** 仅当前 owner 可释放，避免旧请求删除新请求的处理槽。 */
    private static final String RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] .. '|PROCESSING' then "
            + "  return redis.call('DEL', KEYS[1]) end "
            + "return 0";

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
        String key = DEDUPE_KEY_PREFIX + sha256Hex(payload);
        String owner = UUID.randomUUID().toString();
        Claim claim;
        try {
            claim = claim(key, owner);
        } catch (Exception e) {
            throw new IllegalStateException("notification dispatch claim failed", e);
        }
        if (claim == Claim.DONE) {
            log.info("notification dispatch duplicated, skip sha256={}", sha256Hex(payload));
            return;
        }
        if (claim == Claim.BUSY) {
            throw new IllegalStateException("notification dispatch still processing sha256=" + sha256Hex(payload));
        }
        try {
            NotificationDispatchMessage msg = objectMapper.readValue(payload, NotificationDispatchMessage.class);
            dispatcher.dispatch(msg);
            markDone(key, owner);
        } catch (Exception e) {
            release(key, owner);
            log.error("failed to process notification dispatch sha256={}", sha256Hex(payload), e);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("notification dispatch failed", e);
        }
    }

    private Claim claim(String key, String owner) {
        Long result = eval(CLAIM_LUA, key, owner, String.valueOf(PROCESSING_TTL.toMillis()));
        if (result != null && result == 1) {
            return Claim.ACQUIRED;
        }
        if (result != null && result == 0) {
            return Claim.DONE;
        }
        return Claim.BUSY;
    }

    private void markDone(String key, String owner) {
        Long result = eval(MARK_DONE_LUA, key, owner, String.valueOf(DONE_TTL.toMillis()));
        if (result == null || result != 1) {
            throw new IllegalStateException("notification dispatch lost dedupe ownership");
        }
    }

    private void release(String key, String owner) {
        try {
            eval(RELEASE_LUA, key, owner);
        } catch (Exception releaseError) {
            log.error("failed to release dispatch dedupe key sha256={}", sha256Hex(key), releaseError);
        }
    }

    private Long eval(String lua, String key, String... values) {
        return redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER, List.of(key), values);
    }

    private enum Claim { ACQUIRED, DONE, BUSY }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
