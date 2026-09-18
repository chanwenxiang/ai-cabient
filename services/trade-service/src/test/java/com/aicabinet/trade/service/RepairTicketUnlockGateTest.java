package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.RepairTicket;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.RepairTicketEventMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H29：维修 DONE 解锁前必须确认同设备没有其它未完结工单。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepairTicketUnlockGateTest {

    @Mock private RepairTicketMapper ticketMapper;
    @Mock private RepairTicketEventMapper eventMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;

    private RepairTicketService service;

    @BeforeEach
    void setUp() {
        service = new RepairTicketService(ticketMapper, eventMapper, deviceInfoMapper,
                permissionService, merchantScopeService, salesLockService, opsExceptionService,
                distributedLockService, null, displaySnapshotHelper);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        lenient().doNothing().when(merchantScopeService).requireDeviceAccess(anyLong(), anyString());
    }

    private RepairTicket ticket(long id, String deviceId, String status) {
        RepairTicket ticket = new RepairTicket();
        ticket.setTicketId(id);
        ticket.setDeviceId(deviceId);
        ticket.setStatus(status);
        ticket.setCreatedAt(java.time.Instant.now());
        ticket.setUpdatedAt(java.time.Instant.now());
        return ticket;
    }

    private DeviceInfo lockedDevice(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setSalesLocked(true);
        return device;
    }

    @Test
    void doneWithUnlock_otherOpenTicketExists_deviceStaysLocked() {
        RepairTicket done = ticket(1L, "DEV-H29", "IN_PROGRESS");
        when(ticketMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(done));
        when(deviceInfoMapper.selectById("DEV-H29")).thenReturn(lockedDevice("DEV-H29"));
        // 同设备还有另一张 OPEN 工单
        when(ticketMapper.selectCount(any())).thenReturn(1L);

        service.transition(9L, 1L, "DONE", "修好了", true);

        verify(salesLockService, never()).applySalesLock(anyLong(), any(), anyBoolean(), anyString(), anyBoolean());
        verify(opsExceptionService, never()).resolveSystem(anyString(), anyString(), anyString());
        assertEquals("DONE", done.getStatus(), "自身工单照常完结");
    }

    @Test
    void doneWithUnlock_noOtherOpenTicket_deviceUnlocked() {
        RepairTicket done = ticket(2L, "DEV-H29B", "IN_PROGRESS");
        when(ticketMapper.findByIdForUpdate(2L)).thenReturn(Optional.of(done));
        DeviceInfo device = lockedDevice("DEV-H29B");
        when(deviceInfoMapper.selectById("DEV-H29B")).thenReturn(device);
        when(ticketMapper.selectCount(any())).thenReturn(0L);
        when(salesLockService.applySalesLock(eq(9L), eq(device), eq(false), anyString(), eq(true)))
                .thenReturn("LOCAL-test");

        service.transition(9L, 2L, "DONE", "修好了", true);

        verify(salesLockService).applySalesLock(eq(9L), eq(device), eq(false), anyString(), eq(true));
        verify(opsExceptionService).resolveSystem(eq("DEVICE_FAULT"), eq("DEV-H29B"), anyString());
    }
}
