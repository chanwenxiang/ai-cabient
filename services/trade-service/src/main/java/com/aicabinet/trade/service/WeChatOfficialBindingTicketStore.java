package com.aicabinet.trade.service;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class WeChatOfficialBindingTicketStore {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "aicabinet:wechat-official:bind:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedissonClient redisson;

    public WeChatOfficialBindingTicketStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public record Issued(String ticket, long userId, String appId, Instant expireAt) {}

    public Issued issue(long userId, String appId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expireAt = Instant.now().plus(TTL);
        String raw = userId + "|" + appId + "|" + expireAt.toEpochMilli();
        redisson.getBucket(KEY_PREFIX + hash(ticket), StringCodec.INSTANCE).set(raw, TTL);
        return new Issued(ticket, userId, appId, expireAt);
    }

    public Optional<Issued> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + hash(ticket.trim()), StringCodec.INSTANCE);
        String raw = bucket.getAndDelete();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split("\\|", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(parts[0]);
            Instant expireAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
            if (!expireAt.isAfter(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new Issued(ticket.trim(), userId, parts[1], expireAt));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String hash(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
