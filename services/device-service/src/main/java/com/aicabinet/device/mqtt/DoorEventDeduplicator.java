package com.aicabinet.device.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
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

    // ── A6：同 session+同门状态 转发滑动窗口上限 ─────────────────────────────
    // 去重键含设备可控 eventSeq（重投幂等所必需），改值即可绕过 60s 去重刷重复转发
    // （trade 侧状态机兜底，但告警噪音）。此处加第二层限流：同 (sessionId, doorState)
    // 在窗口内的**转发次数**（含不同 eventSeq）超上限即拒绝转发。阈值取 30/60s——
    // 正常开关门翻转远低于此（≥0.5 次/秒持续 1 分钟必为异常/伪造），宽松阈值不误伤。
    // 本地内存窗口（实例级）；多实例部署各自限 N，可接受（N 已远高于合法频率）。
    private static final int RATE_LIMIT_PER_WINDOW = 30;

    private final ConcurrentHashMap<String, ArrayDeque<Long>> forwardWindows = new ConcurrentHashMap<>();

    /**
     * 转发前领取窗口名额：同 (sessionId, doorState) 60s 内超过 30 次返回 false
     * （调用方应丢弃该事件并 ACK，记 {@code rate_limited} 指标）。
     */
    public boolean tryAcquireForwardSlot(String sessionId, String doorState) {
        if (sessionId == null || doorState == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        ArrayDeque<Long> window = forwardWindows.computeIfAbsent(
                sessionId + ":" + doorState, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > TTL_MS) {
                window.pollFirst();
            }
            if (window.size() >= RATE_LIMIT_PER_WINDOW) {
                return false;
            }
            window.addLast(now);
            return true;
        }
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
