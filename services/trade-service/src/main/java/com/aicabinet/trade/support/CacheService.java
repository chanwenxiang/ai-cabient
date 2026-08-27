package com.aicabinet.trade.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 轻量级本地缓存，TTL 过期后自动清除。
 * 生产环境可替换为 Redis，当前实现无外部依赖、部署安全。
 * 缓存键约定: {prefix}:{key}
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String KEY_PREFIX = "aicabinet:cache:";

    private static class CacheEntry {
        final Object value;
        final long expiresAt;
        CacheEntry(Object value, long ttlMs) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> hitCount = new ConcurrentHashMap<>();
    private final Map<String, Long> missCount = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CacheService(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
        this.redis = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    /**
     * 获取缓存，不存在或已过期则通过 loader 加载并缓存。
     * @param prefix 分组前缀，如 "dashboard:stats"
     * @param key 缓存键
     * @param ttlMs 过期时间(毫秒)
     * @param loader 重新加载数据的函数
     */
    public <T> T get(String prefix, String key, long ttlMs, Supplier<T> loader) {
        String cacheKey = KEY_PREFIX + prefix + ":" + key;
        if (redis != null) {
            try {
                String raw = redis.opsForValue().get(cacheKey);
                if (raw != null) {
                    hitCount.merge(prefix, 1L, Long::sum);
                    @SuppressWarnings("unchecked")
                    T cached = (T) deserialize(raw);
                    return cached;
                }
            } catch (Exception e) {
                log.warn("redis cache read failed, fallback local: {}", e.toString());
            }
        }
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            hitCount.merge(prefix, 1L, Long::sum);
            @SuppressWarnings("unchecked")
            T cached = (T) entry.value;
            return cached;
        }
        missCount.merge(prefix, 1L, Long::sum);
        T value = loader.get();
        if (value != null) {
            cache.put(cacheKey, new CacheEntry(value, ttlMs));
            if (redis != null) {
                try {
                    redis.opsForValue().set(cacheKey, serialize(value), Duration.ofMillis(ttlMs));
                } catch (Exception e) {
                    log.warn("redis cache write failed: {}", e.toString());
                }
            }
        }
        return value;
    }

    /**
     * 默认 TTL 5 分钟。
     */
    public <T> T get(String prefix, String key, Supplier<T> loader) {
        return get(prefix, key, 300_000L, loader);
    }

    /**
     * 清除指定前缀的所有缓存（数据变更后调用）。
     */
    public void evict(String prefix) {
        long before = cache.size();
        String redisPrefix = KEY_PREFIX + prefix + ":";
        cache.entrySet().removeIf(e -> e.getKey().startsWith(redisPrefix));
        long evicted = before - cache.size();
        if (redis != null) {
            try (var cursor = redis.scan(ScanOptions.scanOptions().match(redisPrefix + "*").count(200).build())) {
                cursor.forEachRemaining(redis::delete);
            } catch (Exception e) {
                log.warn("redis cache evict failed: {}", e.toString());
            }
        }
        if (evicted > 0) {
            log.debug("cache evicted prefix={} items={}", prefix, evicted);
        }
    }

    /**
     * 清除所有缓存。
     */
    public void evictAll() {
        cache.clear();
        if (redis != null) {
            try (var cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
                cursor.forEachRemaining(redis::delete);
            } catch (Exception e) {
                log.warn("redis cache evictAll failed: {}", e.toString());
            }
        }
        log.info("cache fully evicted");
    }

    private record Envelope(String type, String json) {}

    private String serialize(Object value) throws Exception {
        return objectMapper.writeValueAsString(
                new Envelope(value.getClass().getName(), objectMapper.writeValueAsString(value)));
    }

    private Object deserialize(String raw) throws Exception {
        Envelope envelope = objectMapper.readValue(raw, Envelope.class);
        Class<?> type = Class.forName(envelope.type());
        return objectMapper.readValue(envelope.json(), type);
    }

    /**
     * 后台清理过期条目（可定时调用）。
     */
    public void purgeExpired() {
        long before = cache.size();
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        long removed = before - cache.size();
        if (removed > 0) {
            log.debug("cache purge expired={}", removed);
        }
    }

    // ── 监控 ──

    public int size() { return cache.size(); }
    public long hits(String prefix) { return hitCount.getOrDefault(prefix, 0L); }
    public long misses(String prefix) { return missCount.getOrDefault(prefix, 0L); }
    public int totalSize() { return cache.size(); }
}
