package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseTransferLineMapper;
import com.aicabinet.trade.mapper.WarehouseTransferOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseTransferConcurrencyTest {

    @Mock private WarehouseTransferOrderMapper orderMapper;
    @Mock private WarehouseTransferLineMapper lineMapper;
    @Mock private WarehouseMapper warehouseMapper;
    @Mock private WarehouseService warehouseService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseTransferService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseTransferService(orderMapper, lineMapper, warehouseMapper,
                warehouseService, permissionService, auditService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void ship_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                WarehouseTransferService.transferLockKey(7L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ship(1L, 7L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void ship_whenTransferNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                WarehouseTransferService.transferLockKey(7L), 60L, 5L))
                .thenReturn(true);
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.ship(1L, 7L));

        verify(distributedLockService).unlock(WarehouseTransferService.transferLockKey(7L));
    }
}
