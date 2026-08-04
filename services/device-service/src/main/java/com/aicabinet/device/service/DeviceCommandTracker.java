package com.aicabinet.device.service;

import com.aicabinet.device.metrics.DeviceMqttMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private final Map<String, PendingCommand> pending = new ConcurrentHashMap<>();
    private final Map<String, CommandStatus> recent = new ConcurrentHashMap<>();
    private final DeviceMqttMetrics metrics;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-command-timeout");
        t.setDaemon(true);
        return t;
    });

    public DeviceCommandTracker(DeviceMqttMetrics metrics) {
        this.metrics = metrics;
        executor.scheduleWithFixedDelay(this::expireCommands, 5, 5, TimeUnit.SECONDS);
    }

    public void recordPublished(String commandId, String deviceId, String sessionId) {
        long now = Instant.now().toEpochMilli();
        pending.put(commandId, new PendingCommand(deviceId, sessionId, now));
        recent.put(commandId, new CommandStatus(commandId, deviceId, sessionId, "PENDING", null, now, null));
        trimRecent();
        metrics.recordCommandPublished();
    }

    public void recordAck(String commandId, boolean success) {
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
                .limit(recent.size() - MAX_RECENT_COMMANDS)
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

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private record PendingCommand(String deviceId, String sessionId, long createdAtMs) {}

    public record CommandStatus(String commandId, String deviceId, String sessionId,
                                String status, Boolean success, Long publishedAtMs, Long updatedAtMs) {}
}
