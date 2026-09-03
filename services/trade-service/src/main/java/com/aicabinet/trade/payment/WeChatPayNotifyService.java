package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class WeChatPayNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayNotifyService.class);

    /** 微信要求：通知时效一般 5 分钟内。 */
    private static final long TIMESTAMP_SKEW_SECONDS = 300L;
    private static final Duration NONCE_TTL = Duration.ofMinutes(10);
    private static final String NONCE_KEY_PREFIX = "aicabinet:wxpay:notify:nonce:";

    private final WeChatPayProperties properties;
    private final WeChatPayV3Client v3Client;
    private final WeChatPayV3Aead aead;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;

    public WeChatPayNotifyService(WeChatPayProperties properties,
                                  WeChatPayV3Client v3Client,
                                  WeChatPayV3Aead aead,
                                  ObjectMapper objectMapper,
                                  RedissonClient redisson) {
        this.properties = properties;
        this.v3Client = v3Client;
        this.aead = aead;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
    }

    public JsonNode parseAndVerify(String body,
                                   String timestamp,
                                   String nonce,
                                   String signature,
                                   String serial) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }
        assertFreshTimestamp(timestamp);
        assertNonceOnce(nonce);
        if (!v3Client.verifyNotifySignature(timestamp, nonce, body, signature, serial)) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.path("resource");
            String plain = aead.decrypt(
                    properties.apiV3Key(),
                    resource.path("associated_data").asText(""),
                    resource.path("nonce").asText(""),
                    resource.path("ciphertext").asText("")
            );
            JsonNode decrypted = objectMapper.readTree(plain);
            assertMchIdMatches(decrypted);
            return decrypted;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("wechat notify parse failed", e);
        }
    }

    private void assertMchIdMatches(JsonNode decrypted) {
        String expected = properties.mchId();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String mchid = textOrEmpty(decrypted, "mchid");
        if (mchid.isEmpty()) {
            mchid = textOrEmpty(decrypted.path("payer"), "mchid");
        }
        // 解密资源里常见字段为 mchid；缺失时不硬拒（部分事件体裁不同），有值则必须匹配
        if (!mchid.isEmpty() && !expected.trim().equals(mchid.trim())) {
            log.warn("wechat notify mchid mismatch expected={} actual={}", expected, mchid);
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
    }

    private void assertFreshTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > TIMESTAMP_SKEW_SECONDS) {
            log.warn("wechat notify timestamp skew now={} ts={}", now, ts);
            throw new IllegalArgumentException(ApiMessages.WECHAT_NOTIFY_EXPIRED);
        }
    }

    private void assertNonceOnce(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        String key = NONCE_KEY_PREFIX + nonce.trim();
        RBucket<String> bucket = redisson.getBucket(key, StringCodec.INSTANCE);
        boolean first = bucket.setIfAbsent("1", NONCE_TTL);
        if (!first) {
            log.warn("wechat notify nonce replay key={}", key);
            throw new IllegalArgumentException(ApiMessages.WECHAT_NOTIFY_REPLAY);
        }
    }
}
