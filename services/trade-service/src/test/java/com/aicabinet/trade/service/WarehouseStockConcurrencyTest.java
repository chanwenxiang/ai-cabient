package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseStockConcurrencyTest {

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
    void adjustStocktake_whenStockLockBusy_rejectsWithConflict() {
        Warehouse wh = new Warehouse();
        wh.setWarehouseId("WH-1");
        when(warehouseRepository.findById("WH-1")).thenReturn(Optional.of(wh));
        when(distributedLockService.tryLock(
                eq(WarehouseService.stockLockKey("WH-1", "SKU-1", "B-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adjustStocktake("WH-1", "SKU-1", "B-1",
                        LocalDate.now(), LocalDate.now().plusDays(30),
                        5, 8, 1L, 99L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void adjustStocktake_whenStockInsufficient_unlocksStockLock() {
        Warehouse wh = new Warehouse();
        wh.setWarehouseId("WH-2");
        when(warehouseRepository.findById("WH-2")).thenReturn(Optional.of(wh));
        when(distributedLockService.tryLock(
                eq(WarehouseService.stockLockKey("WH-2", "SKU-2", "B-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(inventoryRepository.findByWarehouseIdAndSkuIdAndBatchNoForUpdate("WH-2", "SKU-2", "B-2"))
                .thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.adjustStocktake("WH-2", "SKU-2", "B-2",
                        LocalDate.now(), LocalDate.now().plusDays(30),
                        10, 5, 1L, 99L));

        verify(distributedLockService).unlock(WarehouseService.stockLockKey("WH-2", "SKU-2", "B-2"));
    }
}
