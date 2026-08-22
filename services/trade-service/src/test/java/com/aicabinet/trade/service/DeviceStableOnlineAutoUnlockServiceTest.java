package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceStableOnlineAutoUnlockServiceTest {

    private final SystemConfigService systemConfig = mock(SystemConfigService.class);
    private final DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
    private final ShoppingSessionMapper sessions = mock(ShoppingSessionMapper.class);
    private final RepairTicketMapper tickets = mock(RepairTicketMapper.class);
    private final OpsExceptionMapper exceptions = mock(OpsExceptionMapper.class);
    private final DeviceSalesLockService salesLock = mock(DeviceSalesLockService.class);
    private final OpsExceptionService opsExceptionService = mock(OpsExceptionService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final DistributedLockService distributedLockService = mock(DistributedLockService.class);

    private DeviceStableOnlineAutoUnlockService service() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        return new DeviceStableOnlineAutoUnlockService(
                systemConfig, devices, sessions, tickets, exceptions, salesLock, opsExceptionService, audit,
                distributedLockService);
    }

    @Test
    void disabledByDefaultDoesNothing() {
        when(systemConfig.getBoolean(anyString(), eq(false))).thenReturn(false);
        when(systemConfig.getInt(anyString(), eq(15))).thenReturn(15);

        assertEquals(0, service().autoUnlockStableOnlineDevices());
        verifyNoInteractions(devices, salesLock);
    }

    @Test
    void unlocksDeviceStableOnlineWithoutSessionOrTicket() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 15))
                .thenReturn(15);
        DeviceInfo device = lockedDevice("CAB-001");
        when(devices.findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), any(Instant.class), eq(200)))
                .thenReturn(List.of(device));
        when(exceptions.findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                eq("DEVICE_FAULT"), eq("CAB-001"), any()))
                .thenReturn(Optional.of(new OpsException()));
        when(tickets.selectCount(any())).thenReturn(0L);
        when(sessions.selectCount(any())).thenReturn(0L);
        when(devices.findByIdForUpdate("CAB-001")).thenReturn(Optional.of(device));
        when(salesLock.applySalesLock(eq(0L), eq(device), eq(false), anyString(), eq(true)))
                .thenReturn("LOCAL-test");

        assertEquals(1, service().autoUnlockStableOnlineDevices());
        verify(salesLock).applySalesLock(0L, device, false, "stable-online-auto-unlock", true);
        verify(opsExceptionService).resolveSystem(eq("DEVICE_FAULT"), eq("CAB-001"), anyString());
        verify(opsExceptionService).resolveSystem(eq("DEVICE_OFFLINE"), eq("CAB-001"), anyString());
        verify(audit).record(eq(0L), eq("DEVICE_AUTO_UNLOCK_STABLE_ONLINE"),
                eq("DEVICE"), eq("CAB-001"), anyString());
    }

    @Test
    void skipsDeviceWithOpenRepairTicket() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 15))
                .thenReturn(15);
        DeviceInfo device = lockedDevice("CAB-002");
        when(devices.findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), any(Instant.class), eq(200)))
                .thenReturn(List.of(device));
        when(exceptions.findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                eq("DEVICE_FAULT"), eq("CAB-002"), any()))
                .thenReturn(Optional.of(new OpsException()));
        when(tickets.selectCount(any())).thenReturn(1L);

        assertEquals(0, service().autoUnlockStableOnlineDevices());
        verifyNoInteractions(salesLock);
    }

    @Test
    void skipsDeviceLockedManuallyWithoutFault() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 15))
                .thenReturn(15);
        DeviceInfo device = lockedDevice("CAB-003");
        when(devices.findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), any(Instant.class), eq(200)))
                .thenReturn(List.of(device));
        when(exceptions.findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                eq("DEVICE_FAULT"), eq("CAB-003"), any()))
                .thenReturn(Optional.empty());

        assertEquals(0, service().autoUnlockStableOnlineDevices());
        verifyNoInteractions(salesLock);
    }

    private static DeviceInfo lockedDevice(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setOnlineStatus("ONLINE");
        device.setSalesLocked(true);
        return device;
    }
}
