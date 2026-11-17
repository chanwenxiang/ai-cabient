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
        // H13: notify_id 去重占位不在验签阶段执行 —— 移到「金额校验通过之后、入账之前」
        // 由 PaymentService.handleAlipayNotify 调用 assertNotifyIdOnce，保证金额不符的通知
        // 不会消耗 notify_id 槽位，且同一 notify_id 只入账一次。
        return new HashMap<>(params);
    }

    /**
     * H13: notify_id 去重占位（金额校验通过后、入账前调用）。
     * 同一 notify_id 第二次到达会抛 {@link ApiMessages#ALIPAY_NOTIFY_REPLAY}。
     */
    public void assertNotifyIdOnce(String notifyId) {
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

    /**
     * MA-314（R23X-03 同族，2026-11-17）：入账抛异常时归还 notify_id 占位。
     *
     * <p>支付宝这一族的占位 TTL 是 <b>24 小时</b>（微信 10 分钟）：入账失败后不归还，
     * 渠道当天所有重投都会被 {@link ApiMessages#ALIPAY_NOTIFY_REPLAY} 挡掉，
     * 「钱已到、余额未加」只能靠人工。入账本身幂等，归还后重投可自愈。</p>
     */
    public void releaseNotifyId(String notifyId) {
        if (notifyId == null || notifyId.isBlank()) {
            return;
        }
        try {
            redisson.getBucket(NOTIFY_ID_KEY_PREFIX + notifyId.trim(), StringCodec.INSTANCE).delete();
            log.info("alipay notify_id released for retry（入账未完成，交回渠道重投）");
        } catch (Exception e) {
            log.warn("alipay notify_id release failed（等 TTL 过期）: {}", e.getMessage());
        }
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

}
