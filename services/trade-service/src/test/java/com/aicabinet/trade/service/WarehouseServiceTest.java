package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock private WarehouseRepository warehouseRepository;
    @Mock private WarehouseInventoryRepository inventoryRepository;
    @Mock private WarehouseInboundRepository inboundRepository;
    @Mock private WarehouseInboundLineRepository inboundLineRepository;
    @Mock private WarehouseOutboundRepository outboundRepository;
    @Mock private WarehouseOutboundLineRepository outboundLineRepository;
    @Mock private WarehouseMovementRepository movementRepository;
    @Mock private DeviceSkuInventoryRepository deviceInventoryRepository;
    @Mock private ReplenishmentTaskRepository taskRepository;
    @Mock private SkuCatalogRepository skuCatalogRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private SalesVelocityService salesVelocityService;
    @Mock private InTransitService inTransitService;

    private WarehouseService warehouseService;

    @Test
    void listWarehouses_shouldReturnAll() {
        var w1 = new Warehouse();
        w1.setWarehouseId("WH-001");
        w1.setWarehouseName("主仓库");

        when(warehouseRepository.findAll()).thenReturn(List.of(w1));
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService);

        var result = warehouseService.listWarehouses();

        assertEquals(1, result.size());
        assertEquals("WH-001", result.get(0).warehouseId());
        assertEquals("主仓库", result.get(0).warehouseName());
        verify(warehouseRepository, times(1)).findAll();
    }

    @Test
    void listWarehouses_shouldReturnEmpty_whenNoData() {
        when(warehouseRepository.findAll()).thenReturn(List.of());
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService);

        var result = warehouseService.listWarehouses();

        assertTrue(result.isEmpty());
    }

    @Test
    void listInventory_shouldFilterByWarehouse() {
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService);

        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001")).thenReturn(List.of());

        var result = warehouseService.listInventory("WH-001");

        assertTrue(result.isEmpty());
        verify(inventoryRepository).findByWarehouseIdOrderByExpiryDateAsc("WH-001");
    }
}
