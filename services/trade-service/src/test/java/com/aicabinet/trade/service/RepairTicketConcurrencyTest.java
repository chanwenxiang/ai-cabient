package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.RepairTicketEventMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepairTicketConcurrencyTest {

    @Mock private RepairTicketMapper ticketMapper;
    @Mock private RepairTicketEventMapper eventMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private PermissionService permissionService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private DistributedLockService distributedLockService;

    private RepairTicketService service;

    @BeforeEach
    void setUp() {
        service = new RepairTicketService(ticketMapper, eventMapper, deviceInfoMapper,
                permissionService, salesLockService, opsExceptionService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void create_whenLockBusy_rejectsWithConflict() {
        when(deviceInfoMapper.selectById("CAB-001")).thenReturn(new DeviceInfo());
        when(distributedLockService.tryLock(
                RepairTicketService.repairDeviceLockKey("CAB-001"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(1L, "CAB-001", "屏幕故障", null, null, null, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void transition_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                RepairTicketService.ticketLockKey(42L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.transition(1L, 42L, "IN_PROGRESS", "start"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void update_whenTicketNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                RepairTicketService.ticketLockKey(7L), 60L, 5L))
                .thenReturn(true);
        when(ticketMapper.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.update(1L, 7L, "title", null, null, null, null));

        verify(distributedLockService).unlock(RepairTicketService.ticketLockKey(7L));
    }
}
