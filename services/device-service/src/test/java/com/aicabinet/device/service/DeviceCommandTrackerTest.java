package com.aicabinet.device.service;

import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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

    /** H55：ACK 来源 deviceId 与命令登记的不一致 → 拒绝，命令保持 PENDING。 */
    @Test
    void recordAck_deviceMismatch_rejected() {
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()));

        tracker.recordPublished("cmd-55", "CAB-001", "sess-55");
        assertFalse(tracker.recordAck("cmd-55", true, "CAB-999"), "伪造来源的 ACK 应被拒");
        assertEquals("PENDING", tracker.getStatus("cmd-55").status());

        assertTrue(tracker.recordAck("cmd-55", true, "CAB-001"), "正确来源的 ACK 应被接受");
        assertEquals("ACKED", tracker.getStatus("cmd-55").status());

        tracker.stop();
    }

    /** H55：topic 无法解析出 deviceId（unknown）时不做校验，避免误杀合法 ACK。 */
    @Test
    void recordAck_unknownSourceDeviceId_skipsDeviceCheck() {
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()));

        tracker.recordPublished("cmd-56", "CAB-001", "sess-56");
        assertTrue(tracker.recordAck("cmd-56", true, "unknown"));
        assertEquals("ACKED", tracker.getStatus("cmd-56").status());

        tracker.stop();
    }

    /** H54：OPEN_DOOR 命令 ACK 超时 → 异步回调 trade openDoorFailed。 */
    @Test
    void openDoorTimeout_notifiesTradeOpenDoorFailed() {
        TradeServiceClient tradeClient = mock(TradeServiceClient.class);
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), null, tradeClient);

        tracker.recordPublished("cmd-54", "CAB-004", "sess-54");
        tracker.forceExpireForTest("cmd-54");

        verify(tradeClient, timeout(2000)).openDoorFailed(eq("sess-54"), anyString());
        assertEquals("TIMEOUT", tracker.getStatus("cmd-54").status());

        tracker.stop();
    }

    /** H54：回调 HTTP 失败仅记 warn，不影响 tracker 状态。 */
    @Test
    void openDoorTimeout_httpFailure_doesNotAffectTrackerStatus() {
        TradeServiceClient tradeClient = mock(TradeServiceClient.class);
        doThrow(new RuntimeException("trade down")).when(tradeClient).openDoorFailed(anyString(), anyString());
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), null, tradeClient);

        tracker.recordPublished("cmd-57", "CAB-005", "sess-57");
        tracker.forceExpireForTest("cmd-57");

        verify(tradeClient, timeout(2000)).openDoorFailed(eq("sess-57"), anyString());
        assertEquals("TIMEOUT", tracker.getStatus("cmd-57").status());

        tracker.stop();
    }

    /** H54：无 sessionId 的命令（SET_TARGET_TEMP 等）超时不回调 trade。 */
    @Test
    void nonSessionCommandTimeout_doesNotNotifyTrade() throws Exception {
        TradeServiceClient tradeClient = mock(TradeServiceClient.class);
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), null, tradeClient);

        tracker.recordPublished("cmd-58", "CAB-006", null);
        tracker.forceExpireForTest("cmd-58");
        Thread.sleep(200);  // 给异步回调一个窗口，确认未发生

        verify(tradeClient, never()).openDoorFailed(anyString(), anyString());
        assertEquals("TIMEOUT", tracker.getStatus("cmd-58").status());

        tracker.stop();
    }
}
