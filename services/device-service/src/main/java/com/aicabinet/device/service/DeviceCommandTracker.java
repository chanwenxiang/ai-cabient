package com.aicabinet.device.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class DeviceCommandTracker {
    private static final String LATE_FAILED_ACK = "LATE_FAILED_ACK";
    private static final String DUPLICATE_ACK = "DUPLICATE_ACK";
    private static final String LATE_ACK = "LATE_ACK";
    private static final String STATUS_PENDING = "PENDING";
    private static final String TIMEOUT = "TIMEOUT";
    private static final String ACKED = "ACKED";

    /** H54：OPEN_DOOR ACK 超时通知 trade 置失败的原因文案。 */
    private static final String OPEN_DOOR_ACK_TIMEOUT_REASON = "开门指令确认超时";

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandTracker.class);
    private static final long ACK_TIMEOUT_MS = 15_000L;
    private static final int MAX_RECENT_COMMANDS = 500;
    /**
     * 过期扫描的分批参数（P3-r19-30）。
     *
     * <p>单批 200 条：一次 ZREM + HGETALL 往返约 1~2ms，200 条 ~ 0.4s，远小于 5s 调度间隔。
     * 每轮最多 5 批 = 1000 条/轮 = 12000 条/分钟；即使停机一天积压 17 万条，也能在一轮
     * 调度周期内收敛，而单轮最长占用约 2s（不超过调度间隔，不会自我堆积）。
     */
    private static final int EXPIRE_SCAN_BATCH = 200;
    private static final int EXPIRE_SCAN_MAX_BATCHES = 5;
    private static final String KEY_PREFIX = "aicabinet:device-cmd:";
    /** 待 ACK 命令索引：score = publishedAtMs，超时用 ZRANGEBYSCORE（B-20，避免全量 SCAN） */
    private static final String PENDING_ZSET = KEY_PREFIX + "pending-z";
    private static final long KEY_TTL_SECONDS = 3600L;

    private final Map<String, PendingCommand> pending = new ConcurrentHashMap<>();
    private final Map<String, CommandStatus> recent = new ConcurrentHashMap<>();
    private final DeviceMqttMetrics metrics;
    private final StringRedisTemplate redis;
    private final TradeServiceClient tradeServiceClient;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-command-timeout");
        t.setDaemon(true);
        return t;
    });

    /**
     * 超时通知专用线程池（R19-18）。
     *
     * <p>原实现用 {@code CompletableFuture.runAsync} 的 <b>commonPool</b>：它同时供并行流使用，
     * 且并行度 = CPU-1。命令超时是「突发批量」场景（一次 MQTT 抖动可同时过期上千条），
     * 上千个 HTTP 回调会把 commonPool 占满，连带拖慢同一 JVM 内所有并行流，而且队列无界
     * ⇒ 堆积到 OOM 之前没有任何信号。
     *
     * <p>这里换成有界池：core=1 / max=2 / 队列 256，拒绝策略 CallerRuns（压回调度线程，
     * 形成背压而不丢通知）。丢通知的代价是「会话靠 trade 侧兜底清扫才置失败」，
     * 比 OOM 更可接受的是排队，但比静默丢弃更可接受的是慢一点。
     */
    private final ExecutorService notifier = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "device-cmd-notify");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 本地模式（单元测试 / 无 Redis 环境）。 */
    public DeviceCommandTracker(DeviceMqttMetrics metrics) {
        this(metrics, null, null);
    }

    /** 兼容构造（无开门超时回调）。 */
    DeviceCommandTracker(DeviceMqttMetrics metrics, StringRedisTemplate redis) {
        this(metrics, redis, null);
    }

    /** Redis 模式：命令状态跨实例共享，Redis 不可用时回退本地。 */
    @Autowired
    public DeviceCommandTracker(DeviceMqttMetrics metrics, StringRedisTemplate redis,
                                TradeServiceClient tradeServiceClient) {
        this.metrics = metrics;
        this.redis = redis;
        this.tradeServiceClient = tradeServiceClient;
        executor.scheduleWithFixedDelay(this::expireCommands, 5, 5, TimeUnit.SECONDS);
    }

    public void recordPublished(String commandId, String deviceId, String sessionId) {
        long now = Instant.now().toEpochMilli();
        CommandStatus status = new CommandStatus(commandId, deviceId, sessionId, STATUS_PENDING, null, now, null);
        if (redis != null) {
            try {
                writeStatus(status);
                metrics.recordCommandPublished();
                return;
            } catch (Exception e) {
                log.warn("redis command publish failed, fallback local: {}", e.toString());
            }
        }
        pending.put(commandId, new PendingCommand(deviceId, sessionId, now));
        recent.put(commandId, status);
        trimRecent();
        metrics.recordCommandPublished();
    }

    public void recordAck(String commandId, boolean success) {
        recordAck(commandId, success, null);
    }

    /**
     * H55：带来源设备校验的 ACK。expectedDeviceId 来自 ACK topic（cabinet/{deviceId}/evt），
     * 与命令登记的 deviceId 不一致时 warn + 忽略（返回 false），命令保持 PENDING。
     */
    public boolean recordAck(String commandId, boolean success, String expectedDeviceId) {
        if (redis != null) {
            try {
                return recordAckRedis(commandId, success, expectedDeviceId);
            } catch (Exception e) {
                log.warn("redis command ack failed, fallback local: {}", e.toString());
            }
        }
        return recordAckLocal(commandId, success, expectedDeviceId);
    }

    private boolean recordAckLocal(String commandId, boolean success, String expectedDeviceId) {
        PendingCommand command = pending.remove(commandId);
        if (command != null) {
            if (deviceMismatch(command.deviceId(), expectedDeviceId)) {
                pending.put(commandId, command);  // 归还：正确来源的重发 ACK 仍可被接受
                log.warn("ACK deviceId mismatch, ignored commandId={} registered={} from={}",
                        commandId, command.deviceId(), expectedDeviceId);
                return false;
            }
            recordPendingAck(commandId, command, success);
            return true;
        }
        CommandStatus existing = recent.get(commandId);
        if (existing != null && deviceMismatch(existing.deviceId(), expectedDeviceId)) {
            log.warn("ACK deviceId mismatch, ignored commandId={} registered={} from={}",
                    commandId, existing.deviceId(), expectedDeviceId);
            return false;
        }
        handleMissingPendingAck(commandId, success);
        return true;
    }

    /** H55：expectedDeviceId 无法解析（null/blank/unknown）时不做校验；两者都非空才比对。 */
    private static boolean deviceMismatch(String registeredDeviceId, String expectedDeviceId) {
        if (expectedDeviceId == null || expectedDeviceId.isBlank() || "unknown".equals(expectedDeviceId)) {
            return false;
        }
        return registeredDeviceId != null && !registeredDeviceId.isBlank()
                && !registeredDeviceId.equals(expectedDeviceId);
    }

    private void handleMissingPendingAck(String commandId, boolean success) {
        CommandStatus existing = recent.get(commandId);
        if (existing != null && isTerminal(existing.status())) {
            recordRepeatedAck(commandId, existing, success);
            return;
        }
        metrics.recordCommandAckUnknown();
        log.warn("received ACK for unknown commandId={} success={}", commandId, success);
        recent.put(commandId, new CommandStatus(commandId, null, null,
                success ? "ACKED_UNKNOWN" : "FAILED_UNKNOWN", success, null, Instant.now().toEpochMilli()));
        trimRecent();
    }

    private void recordRepeatedAck(String commandId, CommandStatus existing, boolean success) {
        String status = resolveRepeatedAckStatus(existing.status(), success);
        recent.put(commandId, new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                status, success, existing.publishedAtMs(), Instant.now().toEpochMilli()));
        trimRecent();
        log.info("received repeated ACK commandId={} previousStatus={} success={}",
                commandId, existing.status(), success);
    }

    private static String resolveRepeatedAckStatus(String previousStatus, boolean success) {
        if (TIMEOUT.equals(previousStatus)) {
            return success ? LATE_ACK : LATE_FAILED_ACK;
        }
        return DUPLICATE_ACK;
    }

    private void recordPendingAck(String commandId, PendingCommand command, boolean success) {
        recent.put(commandId, new CommandStatus(commandId, command.deviceId(), command.sessionId(),
                success ? ACKED : CabinetConstants.ORDER_STATUS_FAILED, success, command.createdAtMs(),
                Instant.now().toEpochMilli()));
        trimRecent();
        if (success) {
            metrics.recordCommandAckSuccess();
        } else {
            metrics.recordCommandAckFailure();
        }
        log.info("device command ACK commandId={} device={} session={} success={}",
                commandId, command.deviceId(), command.sessionId(), success);
    }

    private void expireCommands() {
        if (redis != null) {
            try {
                expireCommandsRedis();
                return;
            } catch (Exception e) {
                log.warn("redis command expiry failed, fallback local: {}", e.toString());
            }
        }
        expireCommandsLocal();
    }

    private void expireCommandsLocal() {
        long now = Instant.now().toEpochMilli();
        Iterator<Map.Entry<String, PendingCommand>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingCommand> entry = it.next();
            PendingCommand command = entry.getValue();
            if (now - command.createdAtMs() >= ACK_TIMEOUT_MS) {
                it.remove();
                expirePendingCommand(entry.getKey(), command, now);
            }
        }
    }

    /** 超时共性处理：记指标 + 置 TIMEOUT + （OPEN_DOOR）异步通知 trade。 */
    private void expirePendingCommand(String commandId, PendingCommand command, long now) {
        metrics.recordCommandAckTimeout();
        recent.put(commandId, new CommandStatus(commandId, command.deviceId(), command.sessionId(),
                TIMEOUT, false, command.createdAtMs(), now));
        trimRecent();
        log.warn("device command ACK timeout commandId={} device={} session={}",
                commandId, command.deviceId(), command.sessionId());
        notifyOpenDoorTimeoutAsync(command.sessionId());
    }

    void forceExpireForTest(String commandId) {
        PendingCommand command = pending.remove(commandId);
        if (command == null) {
            return;
        }
        expirePendingCommand(commandId, command, Instant.now().toEpochMilli());
    }

    /**
     * H54：OPEN_DOOR 是唯一携带 sessionId 的命令类型；其 ACK 超时立即异步回调 trade
     * 把会话置为失败（不等 trade 侧 90s 兜底清扫）。HTTP 失败仅 warn，不影响 tracker 状态。
     */
    private void notifyOpenDoorTimeoutAsync(String sessionId) {
        if (tradeServiceClient == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        notifier.execute(() -> {
            try {
                tradeServiceClient.openDoorFailed(sessionId, OPEN_DOOR_ACK_TIMEOUT_REASON);
            } catch (Exception e) {
                log.warn("notify open-door timeout failed session={}: {}", sessionId, e.toString());
            }
        });
    }

    public CommandStatus getStatus(String commandId) {
        if (redis != null) {
            try {
                CommandStatus status = readStatus(commandId);
                if (status != null) {
                    return status;
                }
            } catch (Exception e) {
                log.warn("redis command status read failed, fallback local: {}", e.toString());
            }
        }
        CommandStatus status = recent.get(commandId);
        if (status != null) {
            return status;
        }
        PendingCommand command = pending.get(commandId);
        if (command == null) {
            return null;
        }
        return new CommandStatus(commandId, command.deviceId(), command.sessionId(),
                STATUS_PENDING, null, command.createdAtMs(), null);
    }

    private void trimRecent() {
        if (recent.size() <= MAX_RECENT_COMMANDS) {
            return;
        }
        recent.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) ->
                        Long.compare(nullToZero(a.updatedAtMs()), nullToZero(b.updatedAtMs()))))
                .limit((long) recent.size() - MAX_RECENT_COMMANDS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(recent::remove);
    }

    private static long nullToZero(Long value) {
        return value != null ? value : 0L;
    }

    private static boolean isTerminal(String status) {
        return ACKED.equals(status)
                || CabinetConstants.ORDER_STATUS_FAILED.equals(status)
                || TIMEOUT.equals(status)
                || DUPLICATE_ACK.equals(status)
                || LATE_ACK.equals(status)
                || LATE_FAILED_ACK.equals(status);
    }

    private boolean recordAckRedis(String commandId, boolean success, String expectedDeviceId) {
        CommandStatus existing = readStatus(commandId);
        if (existing != null && deviceMismatch(existing.deviceId(), expectedDeviceId)) {
            log.warn("ACK deviceId mismatch, ignored commandId={} registered={} from={}",
                    commandId, existing.deviceId(), expectedDeviceId);
            return false;
        }
        long now = Instant.now().toEpochMilli();
        CommandStatus next;
        if (existing == null) {
            metrics.recordCommandAckUnknown();
            log.warn("received ACK for unknown commandId={} success={}", commandId, success);
            next = new CommandStatus(commandId, null, null,
                    success ? "ACKED_UNKNOWN" : "FAILED_UNKNOWN", success, null, now);
        } else if (isTerminal(existing.status())) {
            String status = resolveRepeatedAckStatus(existing.status(), success);
            log.info("received repeated ACK commandId={} previousStatus={} success={}",
                    commandId, existing.status(), success);
            next = new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                    status, success, existing.publishedAtMs(), now);
        } else {
            next = new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                    success ? ACKED : CabinetConstants.ORDER_STATUS_FAILED, success, existing.publishedAtMs(), now);
            if (success) {
                metrics.recordCommandAckSuccess();
            } else {
                metrics.recordCommandAckFailure();
            }
            log.info("device command ACK commandId={} device={} session={} success={}",
                    commandId, existing.deviceId(), existing.sessionId(), success);
        }
        writeStatus(next);
        return true;
    }

    /** 包级可见：供 `DeviceCommandTrackerTest` 直接驱动一轮扫描（调度间隔 5s 不适合做单测）。 */
    void expireCommandsRedis() {
        long now = Instant.now().toEpochMilli();
        long cutoff = now - ACK_TIMEOUT_MS;
        // P3-r19-30（2026-11-12）：原来 rangeByScore 不带 limit，一次把**全部**到期成员拉成
        // Set<String>。正常运行到期命令很少（ACK 超时是异常路径），但 Redis 里出现积压时
        // 这个调用会变成「一次性 N 条 + N 次 ZREM + N 次 HGETALL」的同步阻塞，全压在这条
        // 5s 调度线程上：积压 1 万条 ⇒ 单轮几十秒，看门狗自己先超时。
        // 积压的来源很现实：device-service 停机一天后重启，zset 里全是过期待确认。
        // 改为分批：每轮最多 MAX_BATCHES 批、每批 BATCH 条（默认 1000 条/轮），剩余留给
        // 下轮。被抢占（ZREM 返回 0）的成员已不在 zset 里，所以下一批不会重复取到同一批，
        // 不存在「整批抢不到 ⇒ 死循环」。
        for (int round = 0; round < EXPIRE_SCAN_MAX_BATCHES; round += 1) {
            Set<String> expiredIds = redis.opsForZSet()
                    .rangeByScore(PENDING_ZSET, 0, cutoff, 0, EXPIRE_SCAN_BATCH);
            if (expiredIds == null || expiredIds.isEmpty()) {
                return;
            }
            for (String commandId : expiredIds) {
                // R19-18：多实例（device-service 可横向扩）会各自 rangeByScore 到**同一批**到期命令，
                // 原实现「读到就读状态、写 TIMEOUT、通知 trade」⇒ 同一会话被 N 个实例重复
                // openDoorFailed。trade 侧 CAS 能挡住重复入账，但重复 HTTP + 重复告警都是噪音。
                // 这里用 ZREM 的返回值当**抢占令牌**：只有真正把成员从 zset 删掉的那个实例（返回 1）
                // 才处置；其余实例返回 0，直接跳过。处置后 writeStatus 仍会 remove，等价幂等。
                Long claimed = redis.opsForZSet().remove(PENDING_ZSET, commandId);
                if (claimed == null || claimed == 0L) {
                    continue;
                }
                CommandStatus status = readStatus(commandId);
                if (status == null || !STATUS_PENDING.equals(status.status())) {
                    // 已被 ACK / 已被别的实例处置过：zset 成员已摘除，无需再做任何事。
                    continue;
                }
                writeStatus(new CommandStatus(status.commandId(), status.deviceId(), status.sessionId(),
                        TIMEOUT, false, status.publishedAtMs(), now));
                metrics.recordCommandAckTimeout();
                log.warn("device command ACK timeout commandId={} device={} session={}",
                        status.commandId(), status.deviceId(), status.sessionId());
                notifyOpenDoorTimeoutAsync(status.sessionId());
            }
            if (expiredIds.size() < EXPIRE_SCAN_BATCH) {
                return; // 本批未取满 = 已扫到尾部
            }
        }
        log.info("device command expiry scan hit per-round cap({}x{}), remaining due items deferred to next round",
                EXPIRE_SCAN_MAX_BATCHES, EXPIRE_SCAN_BATCH);
    }

    private void writeStatus(CommandStatus status) {
        String key = KEY_PREFIX + status.commandId();
        redis.opsForHash().putAll(key, Map.of(
                "commandId", status.commandId(),
                "deviceId", nz(status.deviceId()),
                "sessionId", nz(status.sessionId()),
                "status", nz(status.status()),
                "success", status.success() == null ? "" : String.valueOf(status.success()),
                "publishedAtMs", status.publishedAtMs() == null ? "" : String.valueOf(status.publishedAtMs()),
                "updatedAtMs", status.updatedAtMs() == null ? "" : String.valueOf(status.updatedAtMs())));
        redis.expire(key, Duration.ofSeconds(KEY_TTL_SECONDS));
        if (STATUS_PENDING.equals(status.status()) && status.publishedAtMs() != null) {
            redis.opsForZSet().add(PENDING_ZSET, status.commandId(), status.publishedAtMs());
        } else {
            redis.opsForZSet().remove(PENDING_ZSET, status.commandId());
        }
    }

    private CommandStatus readStatus(String commandId) {
        return readStatusByKey(KEY_PREFIX + commandId);
    }

    private CommandStatus readStatusByKey(String key) {
        Map<Object, Object> hash = redis.opsForHash().entries(key);
        if (hash.isEmpty()) {
            return null;
        }
        String commandId = (String) hash.get("commandId");
        if (commandId == null || commandId.isBlank()) {
            return null;
        }
        return new CommandStatus(
                commandId,
                nullable(hash, "deviceId"),
                nullable(hash, "sessionId"),
                nullable(hash, "status"),
                nullableBoolean(hash, "success"),
                nullableLong(hash, "publishedAtMs"),
                nullableLong(hash, "updatedAtMs"));
    }

    private static String nullable(Map<Object, Object> hash, String field) {
        String value = (String) hash.get(field);
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean nullableBoolean(Map<Object, Object> hash, String field) {
        String value = nullable(hash, field);
        return value == null ? null : Boolean.parseBoolean(value);
    }

    private static Long nullableLong(Map<Object, Object> hash, String field) {
        String value = nullable(hash, field);
        return value == null ? null : Long.parseLong(value);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        notifier.shutdownNow();
    }

    private record PendingCommand(String deviceId, String sessionId, long createdAtMs) {}

    public record CommandStatus(String commandId, String deviceId, String sessionId,
                                String status, Boolean success, Long publishedAtMs, Long updatedAtMs) {}
}
