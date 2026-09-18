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
    /** 匿名公开页的滑动窗口（按分钟），与登录态小时窗口区分（L04）。 */
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);
    private static final String KEY_PREFIX = "aicabinet:rate:";

    public static final String ACTION_COUPON_CLAIM = "coupon_claim";
    public static final String ACTION_ORDER_PAY = "order_pay";
    public static final String ACTION_SESSION_CREATE = "session_create";
    public static final String ACTION_OPEN_DOOR = "open_door";
    public static final String ACTION_OPEN_DOOR_DEVICE = "open_door_device";
    public static final String ACTION_OPEN_LANDING = "open_landing";

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

    /**
     * L04：公开匿名页按来源 IP 限流（无登录态、独立分钟窗口）。
     * 超限抛 429；limitPerMinute<=0 / 限流总开关关闭 / IP 缺失时放行。
     */
    public void assertIpAllowed(String action, String clientIp, int limitPerMinute, String message) {
        if (!properties.enabled() || limitPerMinute <= 0
                || clientIp == null || clientIp.isBlank()) {
            return;
        }
        String ip = clientIp.trim();
        String key = KEY_PREFIX + action + ":" + ip;
        RAtomicLong counter = redisson.getAtomicLong(key);
        long count = counter.get();
        if (count >= limitPerMinute) {
            log.warn("rate limit hit action={} ip={} count={} max={}", action, ip, count, limitPerMinute);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
        long next = counter.incrementAndGet();
        if (next == 1) {
            counter.expire(IP_WINDOW);
        }
        if (next > limitPerMinute) {
            // 并发下可能越过阈值：回退一次并拒绝
            counter.decrementAndGet();
            log.warn("rate limit race reject action={} ip={} next={} max={}", action, ip, next, limitPerMinute);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
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
