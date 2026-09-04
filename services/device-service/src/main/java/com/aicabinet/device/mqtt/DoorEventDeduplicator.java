package com.aicabinet.device.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开门事件去重：优先 Redis（多实例共享），Redis 不可用时回退本地内存去重。
 */
@Component
public class DoorEventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(DoorEventDeduplicator.class);
    private static final long TTL_MS = 60_000;
    private static final Duration TTL = Duration.ofMillis(TTL_MS);
    private static final String KEY_PREFIX = "aicabinet:door-dedup:";

    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private volatile boolean redisAvailable = true;

    /** 无 Redis 环境（如单元测试）时的本地去重模式。 */
    public DoorEventDeduplicator() {
        this(null);
    }

    /** 生产环境：Spring 注入 Redis。 */
    @Autowired
    public DoorEventDeduplicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isDuplicate(String sessionId, String doorState) {
        return isDuplicate(sessionId, doorState, "");
    }

    public boolean isDuplicate(String sessionId, String doorState, String fingerprint) {
        if (sessionId == null || doorState == null) {
            return false;
        }
        String key = dedupKey(sessionId, doorState, fingerprint);
        if (redis != null) {
            try {
                Boolean first = redis.opsForValue().setIfAbsent(key, "1", TTL);
                if (!redisAvailable) {
                    redisAvailable = true;
                    log.info("redis door-event dedup recovered");
                }
                return !Boolean.TRUE.equals(first);
            } catch (Exception e) {
                if (redisAvailable) {
                    redisAvailable = false;
                    log.warn("redis door-event dedup unavailable, fallback to local: {}", e.toString());
                }
            }
        }
        return localDuplicate(key);
    }

    /** 转发失败时释放幂等键，允许重投（B-2） */
    public void clear(String sessionId, String doorState, String fingerprint) {
        if (sessionId == null || doorState == null) {
            return;
        }
        String key = dedupKey(sessionId, doorState, fingerprint);
        if (redis != null) {
            try {
                redis.delete(key);
            } catch (Exception e) {
                log.warn("redis door-event dedup clear failed: {}", e.toString());
            }
        }
        recent.remove(key);
    }

    private static String dedupKey(String sessionId, String doorState, String fingerprint) {
        String suffix = sessionId + ":" + doorState + ":" + (fingerprint != null ? fingerprint : "");
        return KEY_PREFIX + suffix;
    }

    private boolean localDuplicate(String key) {
        long now = System.currentTimeMillis();
        Long previous = recent.put(key, now);
        evictExpired(now);
        return previous != null && now - previous < TTL_MS;
    }

    private void evictExpired(long now) {
        if (recent.size() < 256) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TTL_MS) {
                it.remove();
            }
        }
    }
}
