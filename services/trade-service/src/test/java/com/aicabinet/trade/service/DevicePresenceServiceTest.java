package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevicePresenceServiceTest {

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
        when(devices.findById("CAB-002")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit);
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
        when(devices.findById("CAB-003")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit);
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
        when(devices.findById("CAB-001")).thenReturn(Optional.of(device));
        when(devices.save(any(DeviceInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePresenceService service = new DevicePresenceService(
                devices, temperatures, metrics, exceptions, systemConfig, audit);
        service.heartbeat("CAB-001", "0.9.0", null, null);

        assertTrue(device.getUpdatedAt() != null && !device.getUpdatedAt().isBefore(before));
        verify(devices).save(device);
        verify(exceptions).resolveSystem("DEVICE_OFFLINE", "CAB-001", "设备心跳恢复，已自动上线");
    }
}
