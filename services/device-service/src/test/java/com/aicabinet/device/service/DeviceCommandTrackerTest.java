package com.aicabinet.device.service;

import com.aicabinet.device.metrics.DeviceMqttMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeviceCommandTrackerTest {

    @Test
    void recordAck_marksDuplicateAckWithoutLosingOriginalContext() {
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()));

        tracker.recordPublished("cmd-1", "CAB-001", "sess-1");
        tracker.recordAck("cmd-1", true);
        tracker.recordAck("cmd-1", true);

        DeviceCommandTracker.CommandStatus status = tracker.getStatus("cmd-1");
        assertNotNull(status);
        assertEquals("DUPLICATE_ACK", status.status());
        assertEquals("CAB-001", status.deviceId());
        assertEquals("sess-1", status.sessionId());

        tracker.stop();
    }

    @Test
    void forceExpire_marksTimeout() {
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()));

        tracker.recordPublished("cmd-3", "CAB-003", "sess-3");
        tracker.forceExpireForTest("cmd-3");

        DeviceCommandTracker.CommandStatus status = tracker.getStatus("cmd-3");
        assertNotNull(status);
        assertEquals("TIMEOUT", status.status());
        assertEquals("CAB-003", status.deviceId());
        assertEquals("sess-3", status.sessionId());

        tracker.stop();
    }
}
