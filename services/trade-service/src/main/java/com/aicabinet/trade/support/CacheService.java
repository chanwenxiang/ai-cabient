package com.aicabinet.trade.support;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 轻量级本地 + Redis 二级缓存。
 * 防穿透：缓存 null 占位；防雪崩：TTL ±10% 抖动；防击穿：同 key 单飞加载。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String KEY_PREFIX = "aicabinet:cache:";
    /**
     * 集群就绪 P-4：evict 的跨实例失效广播频道。
     * evict 只清本进程 L1 + 删 Redis 键时，其他实例 L1 到期前一直供旧值
     * （lookup 是先 Redis 后 L1，Redis miss 会回落到 L1 旧值，甚至把旧值当 loader
     * 结果跳过——数据刚变过的场景）。发广播让各实例同步清掉自己的 L1。
     */
    public static final String EVICT_CHANNEL = "aicabinet:cache:evict";
    /** 广播消息里的通配前缀：清空全部 L1。 */
    private static final String EVICT_ALL_PREFIX = "*";
    /** 空结果占位；反序列化后仍映射为 null 返回给调用方。 */
    private static final Object NULL_PLACEHOLDER = new Object();
    private static final String NULL_ENVELOPE_TYPE = "__null__";
    private static final long NULL_TTL_MS = 60_000L;

    /** 本实例标识：广播消息回环到自己时跳过（本地已在 evict 里清过）。 */
    private final String instanceId = java.util.UUID.randomUUID().toString();

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
    private final Map<String, ReentrantLock> loadLocks = new ConcurrentHashMap<>();
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
        Lookup<T> first = lookup(prefix, cacheKey);
        if (first.hit()) {
            return first.value();
        }
        ReentrantLock lock = loadLocks.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        lock.lock();
        try {
            Lookup<T> second = lookup(prefix, cacheKey);
            if (second.hit()) {
                return second.value();
            }
            missCount.merge(prefix, 1L, Long::sum);
            T value = loader.get();
            long jitteredTtl = jitterTtl(value == null ? Math.min(ttlMs, NULL_TTL_MS) : ttlMs);
            putValue(cacheKey, value, jitteredTtl);
            return value;
        } finally {
            lock.unlock();
            loadLocks.remove(cacheKey, lock);
        }
    }

    private record Lookup<T>(boolean hit, T value) {}

    private <T> Lookup<T> lookup(String prefix, String cacheKey) {
        if (redis != null) {
            try {
                String raw = redis.opsForValue().get(cacheKey);
                if (raw != null) {
                    hitCount.merge(prefix, 1L, Long::sum);
                    if (isNullEnvelope(raw)) {
                        return new Lookup<>(true, null);
                    }
                    @SuppressWarnings("unchecked")
                    T cached = (T) deserialize(raw);
                    return new Lookup<>(true, cached);
                }
            } catch (Exception e) {
                log.warn("redis cache read failed, fallback local: {}", e.toString());
            }
        }
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            hitCount.merge(prefix, 1L, Long::sum);
            if (entry.value == NULL_PLACEHOLDER) {
                return new Lookup<>(true, null);
            }
            @SuppressWarnings("unchecked")
            T cached = (T) entry.value;
            return new Lookup<>(true, cached);
        }
        return new Lookup<>(false, null);
    }

    private void putValue(String cacheKey, Object value, long ttlMs) {
        Object store = value == null ? NULL_PLACEHOLDER : value;
        cache.put(cacheKey, new CacheEntry(store, ttlMs));
        if (redis != null) {
            try {
                redis.opsForValue().set(cacheKey, serialize(value), Duration.ofMillis(ttlMs));
            } catch (Exception e) {
                log.warn("redis cache write failed: {}", e.toString());
            }
        }
    }

    private static long jitterTtl(long ttlMs) {
        if (ttlMs <= 0) {
            return NULL_TTL_MS;
        }
        // ±10% 抖动，降低同时过期
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Math.max(1_000L, (long) (ttlMs * factor));
    }

    /**
     * 默认 TTL：{@link CacheNames#TTL_DEFAULT_MS}。
     */
    public <T> T get(String prefix, String key, Supplier<T> loader) {
        return get(prefix, key, CacheNames.TTL_DEFAULT_MS, loader);
    }

    /**
     * 清除指定前缀的所有缓存（数据变更后调用）。
     * <p>集群就绪 P-4：本进程 L1 + Redis 键之外，还向 {@link #EVICT_CHANNEL} 广播，
     * 其他实例收到后清各自 L1（广播丢失时仍有 L1 TTL 兜底，语义退化为旧行为）。</p>
     */
    public void evict(String prefix) {
        long before = cache.size();
        evictLocal(prefix);
        long evicted = before - cache.size();
        if (redis != null) {
            try (var cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + prefix + ":*").count(200).build())) {
                cursor.forEachRemaining(redis::delete);
            } catch (Exception e) {
                log.warn("redis cache evict failed: {}", e.toString());
            }
            publishEvict(prefix);
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
            publishEvict(EVICT_ALL_PREFIX);
        }
        log.info("cache fully evicted");
    }

    /** 只清本进程 L1（广播接收方走这里；Redis 键由发起方删除，接收方不重复删）。 */
    private void evictLocal(String prefix) {
        String redisPrefix = KEY_PREFIX + prefix + ":";
        cache.entrySet().removeIf(e -> e.getKey().startsWith(redisPrefix));
    }

    private void publishEvict(String prefix) {
        try {
            redis.convertAndSend(EVICT_CHANNEL, instanceId + "|" + prefix);
        } catch (Exception e) {
            log.warn("cache evict broadcast failed: {}", e.toString());
        }
    }

    /**
     * 收到其他实例的失效广播：清本进程 L1。
     * <p>消息格式 {@code senderInstanceId|prefix}（prefix 为 {@code *} 表示 evictAll）；
     * 自己发的消息跳过——evict 已清过本地。</p>
     */
    public void handleRemoteEvict(String message) {
        int sep = message.indexOf('|');
        if (sep <= 0) {
            return;
        }
        String sender = message.substring(0, sep);
        String prefix = message.substring(sep + 1);
        if (instanceId.equals(sender)) {
            return;
        }
        if (EVICT_ALL_PREFIX.equals(prefix)) {
            cache.clear();
        } else {
            evictLocal(prefix);
        }
        log.debug("cache remote evict prefix={}", prefix);
    }

    private record Envelope(String type, String json) {}

    private String serialize(Object value) throws JsonProcessingException {
        if (value == null) {
            return objectMapper.writeValueAsString(new Envelope(NULL_ENVELOPE_TYPE, "null"));
        }
        return objectMapper.writeValueAsString(
                new Envelope(value.getClass().getName(), objectMapper.writeValueAsString(value)));
    }

    private static boolean isNullEnvelope(String raw) {
        return raw != null && raw.contains("\"" + NULL_ENVELOPE_TYPE + "\"");
    }

    private Object deserialize(String raw) throws JsonProcessingException, ClassNotFoundException {
        Envelope envelope = objectMapper.readValue(raw, Envelope.class);
        if (NULL_ENVELOPE_TYPE.equals(envelope.type())) {
            return null;
        }
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
