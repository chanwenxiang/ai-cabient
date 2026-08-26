package com.aicabinet.device.service;

import com.aicabinet.device.metrics.DeviceMqttMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class DeviceCommandTracker {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandTracker.class);
    private static final long ACK_TIMEOUT_MS = 15_000L;
    private static final int MAX_RECENT_COMMANDS = 500;
    private static final String KEY_PREFIX = "aicabinet:device-cmd:";
    private static final long KEY_TTL_SECONDS = 3600L;

    private final Map<String, PendingCommand> pending = new ConcurrentHashMap<>();
    private final Map<String, CommandStatus> recent = new ConcurrentHashMap<>();
    private final DeviceMqttMetrics metrics;
    private final StringRedisTemplate redis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-command-timeout");
        t.setDaemon(true);
        return t;
    });

    /** 本地模式（单元测试 / 无 Redis 环境）。 */
    public DeviceCommandTracker(DeviceMqttMetrics metrics) {
        this(metrics, null);
    }

    /** Redis 模式：命令状态跨实例共享，Redis 不可用时回退本地。 */
    @Autowired
    public DeviceCommandTracker(DeviceMqttMetrics metrics, StringRedisTemplate redis) {
        this.metrics = metrics;
        this.redis = redis;
        executor.scheduleWithFixedDelay(this::expireCommands, 5, 5, TimeUnit.SECONDS);
    }

    public void recordPublished(String commandId, String deviceId, String sessionId) {
        long now = Instant.now().toEpochMilli();
        CommandStatus status = new CommandStatus(commandId, deviceId, sessionId, "PENDING", null, now, null);
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
        if (redis != null) {
            try {
                recordAckRedis(commandId, success);
                return;
            } catch (Exception e) {
                log.warn("redis command ack failed, fallback local: {}", e.toString());
            }
        }
        recordAckLocal(commandId, success);
    }

    private void recordAckLocal(String commandId, boolean success) {
        PendingCommand command = pending.remove(commandId);
        if (command == null) {
            CommandStatus existing = recent.get(commandId);
            if (existing != null && isTerminal(existing.status())) {
                String status = "TIMEOUT".equals(existing.status())
                        ? (success ? "LATE_ACK" : "LATE_FAILED_ACK")
                        : "DUPLICATE_ACK";
                recent.put(commandId, new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                        status, success, existing.publishedAtMs(), Instant.now().toEpochMilli()));
                trimRecent();
                log.info("received repeated ACK commandId={} previousStatus={} success={}",
                        commandId, existing.status(), success);
                return;
            }
            metrics.recordCommandAckUnknown();
            log.warn("received ACK for unknown commandId={} success={}", commandId, success);
            recent.put(commandId, new CommandStatus(commandId, null, null,
                    success ? "ACKED_UNKNOWN" : "FAILED_UNKNOWN", success, null, Instant.now().toEpochMilli()));
            trimRecent();
            return;
        }
        recent.put(commandId, new CommandStatus(commandId, command.deviceId(), command.sessionId(),
                success ? "ACKED" : "FAILED", success, command.createdAtMs(), Instant.now().toEpochMilli()));
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
                metrics.recordCommandAckTimeout();
                recent.put(entry.getKey(), new CommandStatus(entry.getKey(), command.deviceId(), command.sessionId(),
                        "TIMEOUT", false, command.createdAtMs(), now));
                trimRecent();
                log.warn("device command ACK timeout commandId={} device={} session={}",
                        entry.getKey(), command.deviceId(), command.sessionId());
            }
        }
    }

    void forceExpireForTest(String commandId) {
        PendingCommand command = pending.remove(commandId);
        if (command == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        recent.put(commandId, new CommandStatus(commandId, command.deviceId(), command.sessionId(),
                "TIMEOUT", false, command.createdAtMs(), now));
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
                "PENDING", null, command.createdAtMs(), null);
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
        return "ACKED".equals(status)
                || "FAILED".equals(status)
                || "TIMEOUT".equals(status)
                || "DUPLICATE_ACK".equals(status)
                || "LATE_ACK".equals(status)
                || "LATE_FAILED_ACK".equals(status);
    }

    private void recordAckRedis(String commandId, boolean success) {
        CommandStatus existing = readStatus(commandId);
        long now = Instant.now().toEpochMilli();
        CommandStatus next;
        if (existing == null) {
            metrics.recordCommandAckUnknown();
            log.warn("received ACK for unknown commandId={} success={}", commandId, success);
            next = new CommandStatus(commandId, null, null,
                    success ? "ACKED_UNKNOWN" : "FAILED_UNKNOWN", success, null, now);
        } else if (isTerminal(existing.status())) {
            String status = "TIMEOUT".equals(existing.status())
                    ? (success ? "LATE_ACK" : "LATE_FAILED_ACK")
                    : "DUPLICATE_ACK";
            log.info("received repeated ACK commandId={} previousStatus={} success={}",
                    commandId, existing.status(), success);
            next = new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                    status, success, existing.publishedAtMs(), now);
        } else {
            next = new CommandStatus(commandId, existing.deviceId(), existing.sessionId(),
                    success ? "ACKED" : "FAILED", success, existing.publishedAtMs(), now);
            if (success) {
                metrics.recordCommandAckSuccess();
            } else {
                metrics.recordCommandAckFailure();
            }
            log.info("device command ACK commandId={} device={} session={} success={}",
                    commandId, existing.deviceId(), existing.sessionId(), success);
        }
        writeStatus(next);
    }

    private void expireCommandsRedis() {
        long now = Instant.now().toEpochMilli();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                CommandStatus status = readStatusByKey(cursor.next());
                if (status == null || !"PENDING".equals(status.status())) {
                    continue;
                }
                if (now - status.publishedAtMs() >= ACK_TIMEOUT_MS) {
                    writeStatus(new CommandStatus(status.commandId(), status.deviceId(), status.sessionId(),
                            "TIMEOUT", false, status.publishedAtMs(), now));
                    metrics.recordCommandAckTimeout();
                    log.warn("device command ACK timeout commandId={} device={} session={}",
                            status.commandId(), status.deviceId(), status.sessionId());
                }
            }
        }
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
    }

    private record PendingCommand(String deviceId, String sessionId, long createdAtMs) {}

    public record CommandStatus(String commandId, String deviceId, String sessionId,
                                String status, Boolean success, Long publishedAtMs, Long updatedAtMs) {}
}
