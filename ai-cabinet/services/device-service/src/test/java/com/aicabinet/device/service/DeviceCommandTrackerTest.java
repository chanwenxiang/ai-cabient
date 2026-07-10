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
    void recordAck_marksLateAckForExpiredCommand() {
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()));

        tracker.recordPublished("cmd-2", "CAB-002", "sess-2");
        tracker.forceExpireForTest("cmd-2");
        tracker.recordAck("cmd-2", true);

        DeviceCommandTracker.CommandStatus status = tracker.getStatus("cmd-2");
        assertNotNull(status);
        assertEquals("LATE_ACK", status.status());
        assertEquals("CAB-002", status.deviceId());
        assertEquals("sess-2", status.sessionId());

        tracker.stop();
    }
}
