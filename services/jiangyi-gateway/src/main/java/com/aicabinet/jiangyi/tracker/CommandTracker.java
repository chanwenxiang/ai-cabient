package com.aicabinet.jiangyi.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 *   <li>{@code doing}：设备进入大模型复核——10min 无二次甄别 → recognize-timeout；</li>
 *   <li>{@code model}：模型部署已下发——10min 无回执 → push-failed；同设备 NX 标记最多一条。</li>
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
    private static final String KEY_MODEL = "jiangyi:cmd:model:pending";
    private static final String KEY_MODEL_DEVICE_PREFIX = "jiangyi:cmd:model:device:";
    private static final Duration MODEL_DEVICE_TTL = Duration.ofHours(2);
    private static final RedisScript<Long> REGISTER_MODEL = new DefaultRedisScript<>(
            "local ok = redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[5]); "
                    + "if not ok then return 0 end; "
                    + "redis.call('zadd', KEYS[2], ARGV[4], ARGV[1]); "
                    + "redis.call('hset', KEYS[3], 'deviceId', ARGV[2], 'identifier', ARGV[3], "
                    + "'issuedAt', ARGV[4], 'status', 'PENDING'); "
                    + "redis.call('expire', KEYS[3], 7200); return 1",
            Long.class);
    private static final RedisScript<Long> COMPLETE_MODEL = new DefaultRedisScript<>(
            "if redis.call('hget', KEYS[1], 'deviceId') ~= ARGV[1] then return 0 end; "
                    + "if redis.call('get', KEYS[3]) ~= ARGV[2] then return -1 end; "
                    + "redis.call('zrem', KEYS[2], ARGV[2]); "
                    + "redis.call('hset', KEYS[1], 'status', 'ACKED'); "
                    + "redis.call('del', KEYS[3]); return 1",
            Long.class);
    private static final RedisScript<Long> COMPLETE_MODEL_BY_DEVICE = new DefaultRedisScript<>(
            "local session = redis.call('get', KEYS[1]); "
                    + "if not session then return 0 end; "
                    + "local hkey = ARGV[2] .. session; "
                    + "if redis.call('hget', hkey, 'deviceId') ~= ARGV[1] then return -1 end; "
                    + "redis.call('zrem', KEYS[2], session); "
                    + "redis.call('hset', hkey, 'status', 'ACKED'); "
                    + "redis.call('del', KEYS[1]); return 1",
            Long.class);
    private static final RedisScript<Long> EVICT_MODEL = new DefaultRedisScript<>(
            "local marker = redis.call('hget', KEYS[1], 'deviceId'); "
                    + "if marker and redis.call('get', KEYS[3]) == ARGV[1] then "
                    + "redis.call('del', KEYS[3]) end; "
                    + "redis.call('zrem', KEYS[2], ARGV[1]); redis.call('del', KEYS[1]); return 1",
            Long.class);
    private static final String HASH_FMT = "jiangyi:cmd:%s:%s";
    private static final java.util.regex.Pattern SAFE_SESSION_KEY =
            java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public enum Kind { OPEN, CLOSE, DOING, MODEL }

    private final StringRedisTemplate redis;

    public CommandTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 登记待回执阶段；MODEL 以设备 NX 标记保证同设备最多一个 pending。 */
    public boolean register(Kind kind, String sessionId, String deviceId, String identifier) {
        if (!isSafeSessionKey(sessionId)) {
            // AU-061：body.orderNo 固件不可信——直接 format 进 Redis key 会被注入
            // 空白/冒号/超长串污染键空间；非法字符集或超 64 字符一律不登记（trade 侧自会拒绝该订单）。
            log.warn("jiangyi tracker register SKIPPED (unsafe key) kind={} len={}",
                    kind, sessionId == null ? -1 : sessionId.length());
            return false;
        }
        if (kind == Kind.MODEL) {
            if (deviceId == null || deviceId.isBlank()) {
                log.warn("jiangyi tracker MODEL register REJECTED blank deviceId deploymentId={}", sessionId);
                return false;
            }
            String now = String.valueOf(System.currentTimeMillis());
            Long registered = redis.execute(REGISTER_MODEL,
                    List.of(modelDeviceKey(deviceId), zsetKey(kind), hashKey(kind, sessionId)),
                    sessionId, deviceId, identifier, now, String.valueOf(MODEL_DEVICE_TTL.toMillis()));
            if (registered == null || registered != 1L) {
                log.warn("jiangyi tracker MODEL register REJECTED deviceId={} deploymentId={}",
                        deviceId, sessionId);
                return false;
            }
            log.info("jiangyi tracker registered kind=MODEL sessionId={} deviceId={}", sessionId, deviceId);
            return true;
        }
        writePending(kind, sessionId, deviceId, identifier);
        return true;
    }

    private void writePending(Kind kind, String sessionId, String deviceId, String identifier) {
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
        log.info("jiangyi tracker registered kind={} sessionId={} deviceId={}",
                kind, sessionId, deviceId);
    }

    /**
     * 阶段完成（回执到达）：hash 置 ACKED 并出队。
     * AU-061：仅登记时的目标设备可撤销——伪造他人 orderNo 的回执不得腰斩其兜底登记；
     * 无主 hash 一律拒绝（顺带修复 complete 在无登记时凭空建无 TTL hash 的键泄漏）。
     */
    public boolean complete(Kind kind, String sessionId, String deviceId) {
        if (!isSafeSessionKey(sessionId)) {
            log.warn("jiangyi tracker complete SKIPPED (unsafe key) kind={} len={}",
                    kind, sessionId == null ? -1 : sessionId.length());
            return false;
        }
        String hkey = hashKey(kind, sessionId);
        if (kind == Kind.MODEL) {
            if (deviceId == null || deviceId.isBlank()) {
                return false;
            }
            Long completed = redis.execute(COMPLETE_MODEL,
                    List.of(hkey, zsetKey(kind), modelDeviceKey(deviceId)), deviceId, sessionId);
            if (completed != null && completed == 1L) {
                log.info("jiangyi tracker completed kind=MODEL sessionId={}", sessionId);
                return true;
            }
            log.warn("jiangyi tracker complete REJECTED kind=MODEL sessionId={} caller={}",
                    kind, sessionId, deviceId);
            return false;
        }
        Object owner = redis.opsForHash().get(hkey, "deviceId");
        if (owner == null || !owner.toString().equals(deviceId)) {
            log.warn("jiangyi tracker complete REJECTED kind={} sessionId={} caller={} owner={}",
                    kind, sessionId, deviceId, owner);
            return false;
        }
        redis.opsForZSet().remove(zsetKey(kind), sessionId);
        redis.opsForHash().put(hkey, "status", "ACKED");
        log.info("jiangyi tracker completed kind={} sessionId={}", kind, sessionId);
        return true;
    }

    /**
     * 按设备完成待回执。MODEL 只读取设备 NX 标记指向的那一条部署，
     * 真机空 msgContent 回执因此不会误确认同设备的历史/其它部署。
     */
    public int completeByDevice(Kind kind, String deviceId) {
        if (kind == Kind.MODEL) {
            if (deviceId == null || deviceId.isBlank()) {
                return 0;
            }
            Long completed = redis.execute(COMPLETE_MODEL_BY_DEVICE,
                    List.of(modelDeviceKey(deviceId), zsetKey(kind)), deviceId, "jiangyi:cmd:model:");
            return completed != null && completed == 1L ? 1 : 0;
        }
        int done = 0;
        for (Pending p : due(kind, Long.MAX_VALUE)) {
            if (deviceId != null && deviceId.equals(p.deviceId())
                    && complete(kind, p.sessionId(), deviceId)) {
                done++;
            }
        }
        return done;
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
        if (kind == Kind.MODEL && isSafeSessionKey(sessionId)) {
            Object owner = redis.opsForHash().get(hashKey(kind, sessionId), "deviceId");
            if (owner != null && !owner.toString().isBlank()) {
                redis.execute(EVICT_MODEL,
                        List.of(hashKey(kind, sessionId), zsetKey(kind), modelDeviceKey(owner.toString())),
                        sessionId);
                return;
            }
        }
        redis.opsForZSet().remove(zsetKey(kind), sessionId);
        redis.delete(hashKey(kind, sessionId));
    }

    static String modelDeviceKey(String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(deviceId.trim().getBytes(StandardCharsets.UTF_8));
            return KEY_MODEL_DEVICE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String zsetKey(Kind kind) {
        return switch (kind) {
            case OPEN -> KEY_OPEN;
            case CLOSE -> KEY_CLOSE;
            case DOING -> KEY_DOING;
            case MODEL -> KEY_MODEL;
        };
    }

    private static String hashKey(Kind kind, String sessionId) {
        return (HASH_FMT.formatted(kind.name().toLowerCase(), sessionId));
    }

    /** AU-061：sessionId 进 Redis key 前的字符集/长度约束（我方 sessionId=BizIds 纯数字，留余量兼容）。 */
    private static boolean isSafeSessionKey(String sessionId) {
        return sessionId != null && SAFE_SESSION_KEY.matcher(sessionId).matches();
    }

    /** 超时会话条目。 */
    public record Pending(String sessionId, String deviceId, String identifier, long issuedAtMs) {}
}
