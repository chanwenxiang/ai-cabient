package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseServiceTest {

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

    private WarehouseService warehouseService;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(warehouseService, "self", warehouseService);
    }

    @Test
    void listWarehouses_shouldReturnAll() {
        var w1 = new Warehouse();
        w1.setWarehouseId("WH-001");
        w1.setWarehouseName("主仓库");

        when(warehouseRepository.findAll()).thenReturn(List.of(w1));

        var result = warehouseService.listWarehouses();

        assertEquals(1, result.size());
        assertEquals("WH-001", result.get(0).warehouseId());
        assertEquals("主仓库", result.get(0).warehouseName());
        verify(warehouseRepository, times(1)).findAll();
    }

    @Test
    void listWarehouses_shouldReturnEmpty_whenNoData() {
        when(warehouseRepository.findAll()).thenReturn(List.of());

        var result = warehouseService.listWarehouses();

        assertTrue(result.isEmpty());
    }

    @Test
    void listInventory_shouldFilterByWarehouse() {
        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001")).thenReturn(List.of());

        var result = warehouseService.listInventory("WH-001");

        assertTrue(result.isEmpty());
        verify(inventoryRepository).findByWarehouseIdOrderByExpiryDateAsc("WH-001");
    }

    @Test
    void tryCreateOutboundFromLines_returnsNull_whenZeroStock() {
        stubOutboundSave(501L);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of());

        Long id = warehouseService.tryCreateOutboundFromLines(
                1L, null, 100L, Map.of("SKU-1", 5), null);

        assertNull(id);
        verify(outboundRepository).deleteById(501L);
        verify(outboundLineRepository, never()).save(any());
    }

    @Test
    void tryCreateOutboundFromLines_keepsOutbound_whenPartialStock() {
        stubOutboundSave(502L);
        WarehouseInventory lot = inventoryLot("SKU-1", "B1", 3);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                2L, null, 100L, Map.of("SKU-1", 10), null);

        assertEquals(502L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository).save(lineCaptor.capture());
        assertEquals(3, lineCaptor.getValue().getQuantity());
        verify(outboundRepository, never()).deleteById(anyLong());
    }

    @Test
    void tryCreateOutboundFromLines_allocatesFull_whenEnoughStock() {
        stubOutboundSave(503L);
        WarehouseInventory lot = inventoryLot("SKU-1", "B1", 10);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                3L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), null);

        assertEquals(503L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository).save(lineCaptor.capture());
        assertEquals(5, lineCaptor.getValue().getQuantity());
        verify(outboundRepository, never()).deleteById(anyLong());
    }

    @Test
    void tryCreateOutboundFromLines_fefo_deductsNearerExpiryFirst() {
        stubOutboundSave(504L);
        WarehouseInventory near = inventoryLot("SKU-1", "NEAR", 2, LocalDate.now().plusDays(10));
        WarehouseInventory far = inventoryLot("SKU-1", "FAR", 10, LocalDate.now().plusMonths(6));
        // Repository contract: already ordered by expiry ASC
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(near, far));
        when(outboundLineRepository.sumAllocatedQty(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1", "NEAR")).thenReturn(0);
        when(outboundLineRepository.sumAllocatedQty(
                WarehouseService.DEFAULT_WAREHOUSE_ID, "SKU-1", "FAR")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                4L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), null);

        assertEquals(504L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository, times(2)).save(lineCaptor.capture());
        List<WarehouseOutboundLine> lines = lineCaptor.getAllValues();
        assertEquals("NEAR", lines.get(0).getBatchNo());
        assertEquals(2, lines.get(0).getQuantity());
        assertEquals("FAR", lines.get(1).getBatchNo());
        assertEquals(3, lines.get(1).getQuantity());
    }

    private void stubOutboundSave(long outboundId) {
        when(outboundRepository.save(any(WarehouseOutbound.class))).thenAnswer(inv -> {
            WarehouseOutbound o = inv.getArgument(0);
            // TableId is private; save path relies on MyBatis assigning id — mirror via reflection-free stub
            WarehouseOutbound saved = new WarehouseOutbound();
            saved.setWarehouseId(o.getWarehouseId());
            saved.setRouteId(o.getRouteId());
            saved.setAssigneeUserId(o.getAssigneeUserId());
            saved.setStatus(o.getStatus());
            saved.setNotes(o.getNotes());
            try {
                var field = WarehouseOutbound.class.getDeclaredField("outboundId");
                field.setAccessible(true);
                field.set(saved, outboundId);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return saved;
        });
    }

    private static WarehouseInventory inventoryLot(String skuId, String batchNo, int qty) {
        return inventoryLot(skuId, batchNo, qty, LocalDate.now().plusMonths(6));
    }

    private static WarehouseInventory inventoryLot(String skuId, String batchNo, int qty, LocalDate expiryDate) {
        WarehouseInventory lot = new WarehouseInventory();
        lot.setWarehouseId(WarehouseService.DEFAULT_WAREHOUSE_ID);
        lot.setSkuId(skuId);
        lot.setBatchNo(batchNo);
        lot.setExpiryDate(expiryDate);
        lot.setQuantity(qty);
        return lot;
    }
}
