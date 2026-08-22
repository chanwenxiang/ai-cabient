package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceStableOnlineAutoUnlockConcurrencyTest {

    @Mock private SystemConfigService systemConfig;
    @Mock private DeviceInfoMapper devices;
    @Mock private ShoppingSessionMapper sessions;
    @Mock private RepairTicketMapper tickets;
    @Mock private OpsExceptionMapper exceptions;
    @Mock private DeviceSalesLockService salesLock;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private AdminAuditService audit;
    @Mock private DistributedLockService distributedLockService;

    private DeviceStableOnlineAutoUnlockService service;

    @BeforeEach
    void setUp() {
        service = new DeviceStableOnlineAutoUnlockService(systemConfig, devices, sessions, tickets,
                exceptions, salesLock, opsExceptionService, audit, distributedLockService);
    }

    @Test
    void autoUnlock_whenLockBusy_skipsDevice() {
        when(systemConfig.getBoolean(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false))
                .thenReturn(true);
        when(systemConfig.getInt(SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 15))
                .thenReturn(15);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-AU");
        device.setOnlineStatus("ONLINE");
        device.setSalesLocked(true);
        when(devices.findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
                eq("ONLINE"), any(Instant.class), eq(200)))
                .thenReturn(List.of(device));
        when(exceptions.findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                eq("DEVICE_FAULT"), eq("CAB-AU"), any()))
                .thenReturn(Optional.of(new OpsException()));
        when(tickets.selectCount(any())).thenReturn(0L);
        when(sessions.selectCount(any())).thenReturn(0L);
        when(distributedLockService.tryLock(
                eq(DeviceSalesLockService.deviceSalesLockKey("CAB-AU")), eq(60L), eq(5L)))
                .thenReturn(false);

        assertEquals(0, service.autoUnlockStableOnlineDevices());
        verifyNoInteractions(salesLock);
    }
}
