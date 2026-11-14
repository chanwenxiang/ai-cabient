package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceTemperatureReading;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevicePresenceServiceTest {

    private DistributedLockService lockService() {
        DistributedLockService lock = mock(DistributedLockService.class);
        when(lock.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        return lock;
    }

    @Test
    void heartbeatFromOfflineRecordsOnlineSince() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-002");
        device.setOnlineStatus("OFFLINE");
        when(devices.findByIdForUpdate("CAB-002")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("CAB-002", "0.9.0", null, null);

        assertNotNull(device.getOnlineSince());
        assertEquals("ONLINE", device.getOnlineStatus());
    }

    @Test
    void heartbeatWhileOnlineKeepsOnlineSince() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        Instant since = Instant.now().minusSeconds(300);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-003");
        device.setOnlineStatus("ONLINE");
        device.setOnlineSince(since);
        when(devices.findByIdForUpdate("CAB-003")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("CAB-003", "0.9.0", null, null);

        assertEquals(since, device.getOnlineSince());
    }

    @Test
    void repeatedHeartbeatRefreshesLivenessEvenWhenStatusDoesNotChange() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("Demo");
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus("ONLINE");
        Instant before = Instant.now();
        when(devices.findByIdForUpdate("CAB-001")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("CAB-001", "0.9.0", null, null);

        assertTrue(device.getUpdatedAt() != null && !device.getUpdatedAt().isBefore(before));
        verify(devices).save(device);
        verify(exceptions).resolveSystem("DEVICE_OFFLINE", "CAB-001", "设备心跳恢复，已自动上线");
        verify(exceptions).resolveOfflineAutoLockFault(eq("CAB-001"), anyString());
    }

    /**
     * 2026-11-14 拍板：超温告警这条腿退役（现役将邑柜不报温度，dev 里看到的 `current_temp_c`
     * 全部来自 device-simulator 的默认值 ⇒ 告警只能对模拟器编造的读数报警）。
     *
     * <p>本例是**退役哨兵**：温度故意给到 99℃（旧上限 8 的十几倍），断言
     * ① 读数仍照记（自有柜 MQTT 协议还有温度通道，数据面不跟着退役）、
     * ② 既不报 `TEMP_ABNORMAL` 也不替它结案。谁把告警腿接回来，这一条就会红——
     * 接回来必须带着「温度源可信」的新证据，而不是顺手恢复一个旋钮。</p>
     */
    @Test
    void heartbeatStillRecordsTemperatureButNeverRaisesTempAlert() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-TEMP");
        device.setOnlineStatus("ONLINE");
        when(devices.findByIdForUpdate("CAB-TEMP")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("CAB-TEMP", "0.9.0", null, 99);

        assertEquals(99, device.getCurrentTempC(), "温度读数仍要落库（退役的是告警，不是数据面）");
        verify(temperatures).save(any(DeviceTemperatureReading.class));
        verify(exceptions, never()).report(
                eq("TEMP_ABNORMAL"), anyString(), any(), anyString(), anyString());
        verify(exceptions, never()).resolveSystem(eq("TEMP_ABNORMAL"), anyString(), anyString());
    }

    @Test
    void heartbeatWhileSalesLockedDoesNotClearOfflineAutoLockFault() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-LOCKED");
        device.setOnlineStatus("OFFLINE");
        device.setSalesLocked(true);
        when(devices.findByIdForUpdate("CAB-LOCKED")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("CAB-LOCKED", "0.9.0", null, null);

        verify(exceptions).resolveSystem("DEVICE_OFFLINE", "CAB-LOCKED", "设备心跳恢复，已自动上线");
        org.mockito.Mockito.verify(exceptions, org.mockito.Mockito.never())
                .resolveOfflineAutoLockFault(anyString(), anyString());
    }

    @Test
    void heartbeatBindsImeiWhenEmpty() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("100001");
        device.setOnlineStatus("OFFLINE");
        when(devices.findByIdForUpdate("100001")).thenReturn(Optional.of(device));
        when(devices.findByImei("860000000000001")).thenReturn(Optional.empty());
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.heartbeat("100001", "1.0.0", null, null, "860000000000001", null);

        assertEquals("860000000000001", device.getImei());
    }

    @Test
    void heartbeatWritesLastHeartbeatAt() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-HB");
        device.setOnlineStatus("OFFLINE");
        when(devices.findByIdForUpdate("CAB-HB")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        Instant before = Instant.now().minusSeconds(1);
        service.heartbeat("CAB-HB", "1.0.0", null, null);

        assertNotNull(device.getLastHeartbeatAt());
        assertTrue(!device.getLastHeartbeatAt().isBefore(before));
    }

    @Test
    void markStaleDoesNotAutoLockNeverHeartbeatedDevice() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(eq("device-presence"), eq(600L))).thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_AUTO_LOCK_MINUTES, 10)).thenReturn(10);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, 45))
                .thenReturn(45);
        when(devices.findByOnlineStatusAndUpdatedAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of());

        DeviceInfo neverOnline = new DeviceInfo();
        neverOnline.setDeviceId("NEW-CAB");
        neverOnline.setOnlineStatus("OFFLINE");
        neverOnline.setSalesLocked(false);
        neverOnline.setLastHeartbeatAt(null);
        neverOnline.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(devices.findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(eq("OFFLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of());

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, tasks, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.markStaleDevicesOffline();

        org.mockito.Mockito.verify(devices, org.mockito.Mockito.never()).findByIdForUpdate("NEW-CAB");
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never())
                .appendLog(eq(0L), eq("DEVICE_AUTO_LOCK_OFFLINE"), anyString(), anyString(), anyString());
    }

    @Test
    void markStaleAutoLocksOfflineDeviceAfterLastHeartbeatCutoff() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(eq("device-presence"), eq(600L))).thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_AUTO_LOCK_MINUTES, 10)).thenReturn(10);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, 45))
                .thenReturn(45);
        when(devices.findByOnlineStatusAndUpdatedAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of());

        DeviceInfo stale = new DeviceInfo();
        stale.setDeviceId("CAB-STALE");
        stale.setOnlineStatus("OFFLINE");
        stale.setSalesLocked(false);
        stale.setLastHeartbeatAt(Instant.now().minusSeconds(3600));
        when(devices.findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(eq("OFFLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of(stale));
        when(devices.findByIdForUpdate("CAB-STALE")).thenReturn(Optional.of(stale));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, tasks, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.markStaleDevicesOffline();

        assertTrue(stale.salesLockedEnabled());
        assertTrue(stale.getSalesLockReason() != null && stale.getSalesLockReason().contains("离线超时"));
        verify(audit).appendLog(eq(0L), eq("DEVICE_AUTO_LOCK_OFFLINE"), eq("DEVICE"), eq("CAB-STALE"), anyString());
    }

    @Test
    void markStaleSkipsCandidateWithoutLastHeartbeatEvenIfReturned() {
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceTemperatureReadingMapper temperatures = mock(DeviceTemperatureReadingMapper.class);
        CabinetMetrics metrics = mock(CabinetMetrics.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(eq("device-presence"), eq(600L))).thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_AUTO_LOCK_MINUTES, 10)).thenReturn(10);
        when(systemConfig.getInt(SystemConfigService.DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, 45))
                .thenReturn(45);
        when(devices.findByOnlineStatusAndUpdatedAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of());

        DeviceInfo neverHb = new DeviceInfo();
        neverHb.setDeviceId("CAB-NEVER");
        neverHb.setOnlineStatus("OFFLINE");
        neverHb.setSalesLocked(false);
        neverHb.setLastHeartbeatAt(null);
        when(devices.findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(eq("OFFLINE"), any(Instant.class)))
                .thenReturn(java.util.List.of(neverHb));
        when(devices.findByIdForUpdate("CAB-NEVER")).thenReturn(Optional.of(neverHb));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit, lockService(), null, tasks, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.markStaleDevicesOffline();

        org.junit.jupiter.api.Assertions.assertFalse(neverHb.salesLockedEnabled());
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never())
                .appendLog(eq(0L), eq("DEVICE_AUTO_LOCK_OFFLINE"), anyString(), anyString(), anyString());
    }
}
