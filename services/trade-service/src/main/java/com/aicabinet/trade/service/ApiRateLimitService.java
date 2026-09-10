package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RateLimitProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * 按用户 + 动作的小时窗口限流（Redisson 计数），用于领券、待支付收款、建会话、开门等写接口。
 * 开门额外按设备维度计数，防单用户多柜机刷门。
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
    public static final String ACTION_OPEN_DOOR_DEVICE = "open_door_device";

    private final RedissonClient redisson;
    private final RateLimitProperties properties;

    public ApiRateLimitService(RedissonClient redisson, RateLimitProperties properties) {
        this.redisson = redisson;
        this.properties = properties;
    }

    public void assertCouponClaimAllowed(Long userId) {
        assertAllowed(ACTION_COUPON_CLAIM, String.valueOf(userId), userId, properties.maxCouponClaimsPerHour(),
                ApiMessages.TOO_MANY_COUPON_CLAIMS);
    }

    public void assertOrderPayAllowed(Long userId) {
        assertAllowed(ACTION_ORDER_PAY, String.valueOf(userId), userId, properties.maxOrderPaysPerHour(),
                ApiMessages.TOO_MANY_ORDER_PAYS);
    }

    public void assertSessionCreateAllowed(Long userId) {
        assertAllowed(ACTION_SESSION_CREATE, String.valueOf(userId), userId, properties.maxSessionCreatesPerHour(),
                ApiMessages.TOO_MANY_SESSION_CREATES);
    }

    /**
     * 开门限流：先用户维度，再设备维度（deviceId 为空时仅用户维度）。
     */
    public void assertOpenDoorAllowed(Long userId, String deviceId) {
        assertAllowed(ACTION_OPEN_DOOR, String.valueOf(userId), userId, properties.maxOpenDoorsPerHour(),
                ApiMessages.TOO_MANY_OPENS);
        if (StringUtils.hasText(deviceId)) {
            assertAllowed(ACTION_OPEN_DOOR_DEVICE, deviceId.trim(), userId,
                    properties.maxOpenDoorsPerDevicePerHour(), ApiMessages.TOO_MANY_OPENS);
        }
    }

    void assertAllowed(String action, String subjectKey, Long userId, int maxPerHour, String message) {
        if (!properties.enabled() || userId == null || userId <= 0 || maxPerHour <= 0
                || !StringUtils.hasText(subjectKey)) {
            return;
        }
        String key = KEY_PREFIX + action + ":" + subjectKey;
        RAtomicLong counter = redisson.getAtomicLong(key);
        long count = counter.get();
        if (count >= maxPerHour) {
            log.warn("rate limit hit action={} subject={} userId={} count={} max={}",
                    action, subjectKey, userId, count, maxPerHour);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
        long next = counter.incrementAndGet();
        if (next == 1) {
            counter.expire(WINDOW);
        }
        if (next > maxPerHour) {
            // 并发下可能越过阈值：回退一次并拒绝
            counter.decrementAndGet();
            log.warn("rate limit race reject action={} subject={} userId={} next={} max={}",
                    action, subjectKey, userId, next, maxPerHour);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }
}
