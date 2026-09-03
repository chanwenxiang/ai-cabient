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
        if (jti == null || jti.isBlank()) {
            return;
        }
        Duration ttl = ttlUntil(expiresAt);
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

    private static Duration ttlUntil(Instant expiresAt) {
        if (expiresAt == null) {
            return Duration.ofMinutes(30);
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative()) {
            return Duration.ZERO;
        }
        // 略加缓冲，避免时钟漂移
        return ttl.plusSeconds(30);
    }
}
