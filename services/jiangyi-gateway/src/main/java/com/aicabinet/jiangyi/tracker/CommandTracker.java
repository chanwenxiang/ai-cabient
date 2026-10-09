package com.aicabinet.jiangyi.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将邑指令/阶段追踪（CB-022，方案 §4.1/§4.5）：Redis hash（元数据）+ ZSET（超时队列）。
 *
 * <p>三组 pending（score = 登记时刻 epoch 毫秒，watchdog 按 cutoff 扫描）：</p>
 * <ul>
 *   <li>{@code open}：开门指令已下发——15s 未收到 lockStatus/doorStatus 回执 → open-failed；</li>
 *   <li>{@code close}：door-event CLOSED 已转发——300s 无识别结果 → recognize-timeout；</li>
 *   <li>{@code doing}：设备进入大模型复核——10min 无二次甄别 → recognize-timeout。</li>
 * </ul>
 *
 * <p>hash 只存非敏感元数据（deviceId/identifier/issuedAt/status），不落会话金额。
 * 单 gateway 实例下 ZSET 扫描量极小；多实例由 watchdog 侧 Redis 锁互斥。</p>
 */
@Component
public class CommandTracker {

    private static final Logger log = LoggerFactory.getLogger(CommandTracker.class);

    private static final String KEY_OPEN = "jiangyi:cmd:open:pending";
    private static final String KEY_CLOSE = "jiangyi:cmd:close:pending";
    private static final String KEY_DOING = "jiangyi:cmd:doing:pending";
    private static final String HASH_FMT = "jiangyi:cmd:%s:%s";

    public enum Kind { OPEN, CLOSE, DOING }

    private final StringRedisTemplate redis;

    public CommandTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 登记待回执阶段（幂等：重复登记以最新时间戳为准）。 */
    public void register(Kind kind, String sessionId, String deviceId, String identifier) {
        String zkey = zsetKey(kind);
        String hkey = hashKey(kind, sessionId);
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(zkey, sessionId, now);
        redis.opsForHash().putAll(hkey, Map.of(
                "deviceId", deviceId,
                "identifier", identifier,
                "issuedAt", String.valueOf(now),
                "status", "PENDING"));
        redis.expire(hkey, Duration.ofHours(2));
        log.info("jiangyi tracker registered kind={} sessionId={} deviceId={}", kind, sessionId, deviceId);
    }

    /** 阶段完成（回执到达）：hash 置 ACKED 并出队。 */
    public void complete(Kind kind, String sessionId) {
        redis.opsForZSet().remove(zsetKey(kind), sessionId);
        String hkey = hashKey(kind, sessionId);
        redis.opsForHash().put(hkey, "status", "ACKED");
        log.info("jiangyi tracker completed kind={} sessionId={}", kind, sessionId);
    }

    /** 取登记早于 cutoff 的超时会话（watchdog 扫描用）。 */
    public List<Pending> due(Kind kind, long cutoffEpochMs) {
        Set<ZSetOperations.TypedTuple<String>> hits =
                redis.opsForZSet().rangeByScoreWithScores(zsetKey(kind), Double.NEGATIVE_INFINITY, cutoffEpochMs);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(t -> {
                    String sessionId = t.getValue();
                    Map<Object, Object> meta = redis.opsForHash().entries(hashKey(kind, sessionId));
                    return new Pending(sessionId,
                            meta.get("deviceId") == null ? null : meta.get("deviceId").toString(),
                            meta.get("identifier") == null ? null : meta.get("identifier").toString(),
                            t.getScore() == null ? 0L : t.getScore().longValue());
                })
                .toList();
    }

    /** 出队并清理（处置完成后调用，防重复处置）。 */
    public void evict(Kind kind, String sessionId) {
        redis.opsForZSet().remove(zsetKey(kind), sessionId);
        redis.delete(hashKey(kind, sessionId));
    }

    private static String zsetKey(Kind kind) {
        return switch (kind) {
            case OPEN -> KEY_OPEN;
            case CLOSE -> KEY_CLOSE;
            case DOING -> KEY_DOING;
        };
    }

    private static String hashKey(Kind kind, String sessionId) {
        return (HASH_FMT.formatted(kind.name().toLowerCase(), sessionId));
    }

    /** 超时会话条目。 */
    public record Pending(String sessionId, String deviceId, String identifier, long issuedAtMs) {}
}
