package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        when(systemConfig.getInt(anyString(), eq(5))).thenReturn(5);

        assertEquals(0, service().autoUnlockStableOnlineDevices());
        verifyNoInteractions(devices, salesLock);
    }

    @Test
    void unlocksDeviceStableOnlineWithoutSessionOrTicket() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 5))
                .thenReturn(5);
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
        verify(opsExceptionService).resolveSystem(eq("DEVICE_OFFLINE"), eq("CAB-001"), anyString());
        verify(audit).appendLog(eq(0L), eq("DEVICE_AUTO_UNLOCK_STABLE_ONLINE"),
                eq("DEVICE"), eq("CAB-001"), anyString());
    }

    @Test
    void skipsDeviceWithOpenRepairTicket() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 5))
                .thenReturn(5);
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
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 5))
                .thenReturn(5);
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

    /**
     * 钉住**产品定稿的默认值**：恢复稳定在线 5 分钟后自动解锁（2026-09-17 由 15 下调）。
     *
     * <p>为什么值得单独立一条：其余用例是通过 `getInt(key, 5)` 的 mock 参数**间接**钉住默认值的
     * （参数不匹配会让 stub 落空 → 服务拿到 0 → 直接返回，用例随即失败），可读性差且意图不明。
     * 改这个值时同步点有 3 处：本常量、Flyway 迁移（历史库）、运营台配置行的既有值。</p>
     */
    @Test
    void defaultStableOnlineMinutesIsFive() {
        assertEquals(5, SystemConfigService.DEFAULT_DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES,
                "产品定稿：稳定在线 5 分钟后自动解锁；下调/上调需同步 Flyway 迁移与文档");
    }

    /**
     * 钉住「配置值真的参与 cutoff 计算」：其余用例传的是 `any(Instant.class)`，
     * 阈值即便写错（例如被当成常量忽略）也照样绿。
     */
    @Test
    void cutoffUsesConfiguredStableMinutes() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 5))
                .thenReturn(5);
        when(devices.findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), any(Instant.class), eq(200)))
                .thenReturn(List.of());

        Instant before = Instant.now();
        assertEquals(0, service().autoUnlockStableOnlineDevices());
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(devices).findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), cutoff.capture(), eq(200));
        // 期望 cutoff = 「现在 - 5 分钟」；允许调用前后的时钟抖动各 2 秒
        assertFalse(cutoff.getValue().isBefore(before.minus(5, ChronoUnit.MINUTES).minusSeconds(2)),
                "cutoff 不应早于 now-5min（阈值被放大？）");
        assertFalse(cutoff.getValue().isAfter(after.minus(5, ChronoUnit.MINUTES).plusSeconds(2)),
                "cutoff 不应晚于 now-5min（阈值被缩小？）");
    }

    private static DeviceInfo lockedDevice(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setOnlineStatus("ONLINE");
        device.setSalesLocked(true);
        return device;
    }
}
