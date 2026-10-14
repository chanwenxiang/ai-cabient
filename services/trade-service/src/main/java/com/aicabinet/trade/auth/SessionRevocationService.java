package com.aicabinet.trade.auth;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 会话 JWT 吊销：按 jti 写入 Redis 黑名单，TTL 对齐 token 剩余有效期。
 */
@Service
public class SessionRevocationService {

    private static final String KEY_PREFIX = "aicabinet:auth:deny:jti:";

    private final RedissonClient redisson;

    public SessionRevocationService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public void revoke(String jti, Instant expiresAt) {
        revoke(jti, expiresAt, 0L);
    }

    /**
     * grace 感知吊销：token 已过期但仍在 refresh 宽限期内时，剩余 TTL 取「宽限剩余时间」。
     *
     * <p>成因（2026-10-13 e2e-4 实证）：过期 token 的 exp-now 为负，此前 revoke 直接跳过不写
     * 黑名单 ⇒ 过期但在宽限期内的 token 永远进不了 deny 列表，refresh 轮换与 logout 都拉不住它，
     * 泄露的过期 token 可在宽限窗口内无限重放换新。修复后：未过期 token 维持原 TTL；过期但在
     * 宽限内的 token 以「exp+grace-now」为 TTL 入黑名单（登出/轮换即失效）；宽限也过期的才跳过
     * （本就无任何路径可再用）。</p>
     */
    public void revoke(String jti, Instant expiresAt, long graceSeconds) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        Duration ttl = ttlUntil(expiresAt, graceSeconds);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + jti.trim(), StringCodec.INSTANCE);
        bucket.set("1", ttl);
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return redisson.getBucket(KEY_PREFIX + jti.trim(), StringCodec.INSTANCE).isExists();
    }

    private static Duration ttlUntil(Instant expiresAt, long graceSeconds) {
        if (expiresAt == null) {
            return Duration.ofMinutes(30);
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        // 略加缓冲，避免时钟漂移
        if (ttl.isNegative() && graceSeconds > 0) {
            // 过期但在 refresh 宽限期内：黑名单至少要盖住剩余宽限，否则轮换/登出拦不住重放
            ttl = Duration.between(Instant.now(), expiresAt.plusSeconds(graceSeconds));
        }
        if (ttl.isNegative()) {
            return Duration.ZERO;
        }
        return ttl.plusSeconds(30);
    }
}
