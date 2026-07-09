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

    private final Map<String, PendingCommand> pending = new ConcurrentHashMap<>();
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
        pending.put(commandId, new PendingCommand(deviceId, sessionId, Instant.now().toEpochMilli()));
        metrics.recordCommandPublished();
    }

    public void recordAck(String commandId, boolean success) {
        PendingCommand command = pending.remove(commandId);
        if (command == null) {
            metrics.recordCommandAckUnknown();
            log.warn("received ACK for unknown commandId={} success={}", commandId, success);
            return;
        }
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
                log.warn("device command ACK timeout commandId={} device={} session={}",
                        entry.getKey(), command.deviceId(), command.sessionId());
            }
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private record PendingCommand(String deviceId, String sessionId, long createdAtMs) {}
}
