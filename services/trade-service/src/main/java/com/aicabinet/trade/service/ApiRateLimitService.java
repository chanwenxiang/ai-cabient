package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RateLimitProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * 按用户 + 动作的小时窗口限流（Redisson 计数），用于领券、待支付收款、建会话、开门等写接口。
 */
@Service
public class ApiRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitService.class);
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String KEY_PREFIX = "aicabinet:rate:";

    public static final String ACTION_COUPON_CLAIM = "coupon_claim";
    public static final String ACTION_ORDER_PAY = "order_pay";
    public static final String ACTION_SESSION_CREATE = "session_create";
    public static final String ACTION_OPEN_DOOR = "open_door";

    private final RedissonClient redisson;
    private final RateLimitProperties properties;

    public ApiRateLimitService(RedissonClient redisson, RateLimitProperties properties) {
        this.redisson = redisson;
        this.properties = properties;
    }

    public void assertCouponClaimAllowed(Long userId) {
        assertAllowed(ACTION_COUPON_CLAIM, userId, properties.maxCouponClaimsPerHour(),
                ApiMessages.TOO_MANY_COUPON_CLAIMS);
    }

    public void assertOrderPayAllowed(Long userId) {
        assertAllowed(ACTION_ORDER_PAY, userId, properties.maxOrderPaysPerHour(),
                ApiMessages.TOO_MANY_ORDER_PAYS);
    }

    public void assertSessionCreateAllowed(Long userId) {
        assertAllowed(ACTION_SESSION_CREATE, userId, properties.maxSessionCreatesPerHour(),
                ApiMessages.TOO_MANY_SESSION_CREATES);
    }

    public void assertOpenDoorAllowed(Long userId) {
        assertAllowed(ACTION_OPEN_DOOR, userId, properties.maxOpenDoorsPerHour(),
                ApiMessages.TOO_MANY_OPENS);
    }

    void assertAllowed(String action, Long userId, int maxPerHour, String message) {
        if (!properties.enabled() || userId == null || userId <= 0 || maxPerHour <= 0) {
            return;
        }
        String key = KEY_PREFIX + action + ":" + userId;
        RAtomicLong counter = redisson.getAtomicLong(key);
        long count = counter.get();
        if (count >= maxPerHour) {
            log.warn("rate limit hit action={} userId={} count={} max={}", action, userId, count, maxPerHour);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
        long next = counter.incrementAndGet();
        if (next == 1) {
            counter.expire(WINDOW);
        }
        if (next > maxPerHour) {
            // 并发下可能越过阈值：回退一次并拒绝
            counter.decrementAndGet();
            log.warn("rate limit race reject action={} userId={} next={} max={}", action, userId, next, maxPerHour);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }
}
