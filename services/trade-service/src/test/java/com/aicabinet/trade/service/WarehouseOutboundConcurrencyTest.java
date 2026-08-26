package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.*;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseOutboundConcurrencyTest {

    @Mock private WarehouseMapper warehouseRepository;
    @Mock private WarehouseInventoryMapper inventoryRepository;
    @Mock private WarehouseInboundMapper inboundRepository;
    @Mock private WarehouseInboundLineMapper inboundLineRepository;
    @Mock private WarehouseOutboundMapper outboundRepository;
    @Mock private WarehouseOutboundLineMapper outboundLineRepository;
    @Mock private WarehouseMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper deviceInventoryRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private SalesVelocityService salesVelocityService;
    @Mock private InTransitService inTransitService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void shipOutbound_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(WarehouseService.outboundLockKey(9L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.shipOutbound(1L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void markPicked_whenOutboundNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(WarehouseService.outboundLockKey(8L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(outboundRepository.findByIdForUpdate(8L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.markPicked(8L));

        verify(distributedLockService).unlock(WarehouseService.outboundLockKey(8L));
    }
}
