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
 *
 * <p>M11：键的写入与检查分离——{@link #seen} 只读检查（不写键），
 * {@link #mark} 在**转发成功后**才写键。先写后转发的旧做法在 crash 窗口会丢事件
 * （键已写、事件未转发，重投被误判为重复）；失败不 mark，重投后 seen=false 可再次处理。</p>
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

    /** 只读检查该事件是否已成功转发过（不写键，crash 窗口安全）。 */
    public boolean seen(String sessionId, String doorState, String fingerprint) {
        if (sessionId == null || doorState == null) {
            return false;
        }
        String key = dedupKey(sessionId, doorState, fingerprint);
        if (redis != null) {
            try {
                Boolean exists = redis.hasKey(key);
                if (!redisAvailable) {
                    redisAvailable = true;
                    log.info("redis door-event dedup recovered");
                }
                return Boolean.TRUE.equals(exists);
            } catch (Exception e) {
                if (redisAvailable) {
                    redisAvailable = false;
                    log.warn("redis door-event dedup unavailable, fallback to local: {}", e.toString());
                }
            }
        }
        return localSeen(key);
    }

    /** 转发成功后写幂等键（M11：只处理过的事件才去重）。 */
    public void mark(String sessionId, String doorState, String fingerprint) {
        if (sessionId == null || doorState == null) {
            return;
        }
        String key = dedupKey(sessionId, doorState, fingerprint);
        if (redis != null) {
            try {
                redis.opsForValue().set(key, "1", TTL);
            } catch (Exception e) {
                log.warn("redis door-event dedup mark failed, fallback to local: {}", e.toString());
            }
        }
        recent.put(key, System.currentTimeMillis());
    }

    private static String dedupKey(String sessionId, String doorState, String fingerprint) {
        String suffix = sessionId + ":" + doorState + ":" + (fingerprint != null ? fingerprint : "");
        return KEY_PREFIX + suffix;
    }

    private boolean localSeen(String key) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        Long previous = recent.get(key);
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
