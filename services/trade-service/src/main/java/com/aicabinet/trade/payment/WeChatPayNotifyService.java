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
        // H12: 先验签，再做时效/nonce 防重占位 —— 避免未验签请求先消耗 nonce 槽位
        // （伪造通知可抢先把真实通知的 nonce 占掉，导致真实通知被误判重放）。
        if (!v3Client.verifyNotifySignature(timestamp, nonce, body, signature, serial)) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        assertFreshTimestamp(timestamp);
        assertNonceOnce(nonce);
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
        } catch (Exception e) {
            // 只有验签、时效和 nonce 占位全部成功后才走到这里。解析/解密/商户号校验失败
            // 都还没消费通知，必须归还槽位让渠道重投；REPLAY 在 try 外抛出，绝不能误删他人的占位。
            releaseNonce(nonce);
            if (e instanceof IllegalArgumentException illegalArgument) {
                throw illegalArgument;
            }
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

    /**
     * MA-314（R23X-03 收口，2026-11-17）：入账链路抛异常时**归还** nonce 槽位。
     *
     * <p>nonce 的语义是「同一条通知不要重复入账」，不是「处理失败也不许再试」。入账本身是幂等的
     * （{@code recharge-credit:} / 退款幂等键），所以失败后必须让渠道重投进得来；否则占位到 TTL
     * 过期前（10 分钟）每一次重投都被判 {@code WECHAT_NOTIFY_REPLAY}，钱到没入账只能等查单兜底。</p>
     *
     * <p>删除失败只记日志、不改写原异常：最坏退化成修前形态（等 TTL），不能因为清理动作再抛一个错。</p>
     */
    public void releaseNonce(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return;
        }
        try {
            redisson.getBucket(NONCE_KEY_PREFIX + nonce.trim(), StringCodec.INSTANCE).delete();
            log.info("wechat notify nonce released for retry（入账未完成，交回渠道重投）");
        } catch (Exception e) {
            log.warn("wechat notify nonce release failed（等 TTL 过期）: {}", e.getMessage());
        }
    }
}
