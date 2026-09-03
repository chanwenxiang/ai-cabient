package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class AlipayNotifyService {

    private static final Logger log = LoggerFactory.getLogger(AlipayNotifyService.class);
    private static final Duration NOTIFY_ID_TTL = Duration.ofHours(24);
    private static final String NOTIFY_ID_KEY_PREFIX = "aicabinet:alipay:notify:id:";

    private final AlipayProperties properties;
    private final AlipaySignUtil signUtil;
    private final RedissonClient redisson;

    public AlipayNotifyService(AlipayProperties properties,
                               AlipaySignUtil signUtil,
                               RedissonClient redisson) {
        this.properties = properties;
        this.signUtil = signUtil;
        this.redisson = redisson;
    }

    public Map<String, String> parseAndVerify(Map<String, String> params) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
        }
        String sign = params.get("sign");
        if (!signUtil.verifyRsa2(params, sign, properties.alipayPublicKey())) {
            throw new IllegalArgumentException(ApiMessages.INVALID_ALIPAY_NOTIFY);
        }
        assertAppIdMatches(params);
        assertSellerIdMatches(params);
        assertNotifyIdOnce(params.get("notify_id"));
        return new HashMap<>(params);
    }

    private void assertAppIdMatches(Map<String, String> params) {
        String appId = params.get("app_id");
        if (appId == null || appId.isBlank()) {
            return;
        }
        String expected = properties.appId();
        if (expected != null && !expected.isBlank() && !expected.equals(appId.trim())) {
            log.warn("alipay notify app_id mismatch expected={} actual={}", expected, appId);
            throw new IllegalArgumentException(ApiMessages.INVALID_ALIPAY_NOTIFY);
        }
    }

    private void assertSellerIdMatches(Map<String, String> params) {
        String expected = properties.sellerId();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String sellerId = params.get("seller_id");
        if (sellerId == null || sellerId.isBlank()) {
            // 部分通知不含 seller_id：有配置时拒绝，防漏检
            log.warn("alipay notify missing seller_id while configured");
            throw new IllegalArgumentException(ApiMessages.INVALID_ALIPAY_NOTIFY);
        }
        if (!expected.trim().equals(sellerId.trim())) {
            log.warn("alipay notify seller_id mismatch expected={} actual={}", expected, sellerId);
            throw new IllegalArgumentException(ApiMessages.INVALID_ALIPAY_NOTIFY);
        }
    }

    private void assertNotifyIdOnce(String notifyId) {
        if (notifyId == null || notifyId.isBlank()) {
            return;
        }
        String key = NOTIFY_ID_KEY_PREFIX + notifyId.trim();
        RBucket<String> bucket = redisson.getBucket(key, StringCodec.INSTANCE);
        boolean first = bucket.setIfAbsent("1", NOTIFY_ID_TTL);
        if (!first) {
            log.warn("alipay notify_id replay key={}", key);
            throw new IllegalArgumentException(ApiMessages.ALIPAY_NOTIFY_REPLAY);
        }
    }
}
