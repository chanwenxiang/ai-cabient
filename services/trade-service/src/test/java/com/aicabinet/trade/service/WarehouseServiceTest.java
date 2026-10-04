package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseServiceTest {

    private static final String TEST_WAREHOUSE_ID = "900000000001";

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
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;
    @Mock private DeviceInfoMapper deviceInfoRepository;

    private WarehouseService warehouseService;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService,
                displaySnapshotHelper, deviceInfoRepository, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(warehouseService, "self", warehouseService);
        Warehouse defaultWh = new Warehouse();
        defaultWh.setWarehouseId(TEST_WAREHOUSE_ID);
        defaultWh.setStatus("ACTIVE");
        defaultWh.setManagerUserId(9L);
        when(warehouseRepository.findAll()).thenReturn(List.of(defaultWh));
        when(warehouseRepository.findById(TEST_WAREHOUSE_ID)).thenReturn(Optional.of(defaultWh));
    }

    @Test
    void listWarehouses_shouldReturnAll() {
        var w1 = new Warehouse();
        w1.setWarehouseId("WH-001");
        w1.setWarehouseName("主仓库");

        when(warehouseRepository.searchPage(null, 0, 500)).thenReturn(pageOf(w1));

        var result = warehouseService.listWarehouses();

        assertEquals(1, result.size());
        assertEquals("WH-001", result.get(0).warehouseId());
        assertEquals("主仓库", result.get(0).warehouseName());
        verify(warehouseRepository, times(1)).searchPage(null, 0, 500);
    }

    @Test
    void listWarehouses_shouldReturnEmpty_whenNoData() {
        when(warehouseRepository.searchPage(null, 0, 500)).thenReturn(pageOf());

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
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of());

        Long id = warehouseService.tryCreateOutboundFromLines(
                1L, null, 100L, Map.of("SKU-1", 5), TEST_WAREHOUSE_ID);

        assertNull(id);
        verify(outboundRepository).deleteById(501L);
        verify(outboundLineRepository, never()).save(any());
    }

    @Test
    void tryCreateOutboundFromLines_keepsOutbound_whenPartialStock() {
        stubOutboundSave(502L);
        WarehouseInventory lot = inventoryLot("SKU-1", "B1", 3);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                2L, null, 100L, Map.of("SKU-1", 10), TEST_WAREHOUSE_ID);

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
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                3L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), TEST_WAREHOUSE_ID);

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
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(near, far));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "NEAR")).thenReturn(0);
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "FAR")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                4L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), TEST_WAREHOUSE_ID);

        assertEquals(504L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository, times(2)).save(lineCaptor.capture());
        List<WarehouseOutboundLine> lines = lineCaptor.getAllValues();
        assertEquals("NEAR", lines.get(0).getBatchNo());
        assertEquals(2, lines.get(0).getQuantity());
        assertEquals("FAR", lines.get(1).getBatchNo());
        assertEquals(3, lines.get(1).getQuantity());
    }

    @Test
    void tryCreateOutboundFromLines_usesDeviceHomeWarehouse() {
        stubOutboundSave(505L);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setHomeWarehouseId("WH-SAT");
        when(deviceInfoRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        Warehouse sat = new Warehouse();
        sat.setWarehouseId("WH-SAT");
        sat.setManagerUserId(40L);
        sat.setStatus("ACTIVE");
        when(warehouseRepository.findById("WH-SAT")).thenReturn(Optional.of(sat));
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc("WH-SAT", "SKU-1"))
                .thenReturn(List.of());

        Long id = warehouseService.tryCreateOutboundFromLines(
                5L, "CAB-1", 100L, Map.of("SKU-1", 2), null);

        assertNull(id);
        verify(inventoryRepository).findByWarehouseIdAndSkuIdOrderByExpiryDateAsc("WH-SAT", "SKU-1");
    }

    @Test
    void resolveOutboundWarehouseId_rejectsUnmanagedAndMixedHomes() {
        Warehouse unmanaged = new Warehouse();
        unmanaged.setWarehouseId(TEST_WAREHOUSE_ID);
        unmanaged.setManagerUserId(null);
        when(warehouseRepository.findById(TEST_WAREHOUSE_ID)).thenReturn(Optional.of(unmanaged));
        var unmanagedEx = assertThrows(ResponseStatusException.class,
                () -> warehouseService.resolveOutboundWarehouseId(TEST_WAREHOUSE_ID, null, List.of()));
        assertTrue(String.valueOf(unmanagedEx.getReason()).contains("负责人"));

        DeviceInfo a = new DeviceInfo();
        a.setDeviceId("A");
        a.setHomeWarehouseId("WH-1");
        DeviceInfo b = new DeviceInfo();
        b.setDeviceId("B");
        b.setHomeWarehouseId("WH-2");
        when(deviceInfoRepository.findById("A")).thenReturn(Optional.of(a));
        when(deviceInfoRepository.findById("B")).thenReturn(Optional.of(b));
        ReplenishmentTask t1 = new ReplenishmentTask();
        t1.setDeviceId("A");
        ReplenishmentTask t2 = new ReplenishmentTask();
        t2.setDeviceId("B");
        var mixed = assertThrows(ResponseStatusException.class,
                () -> warehouseService.resolveOutboundWarehouseId(null, null, List.of(t1, t2)));
        assertEquals(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_MIXED, mixed.getReason());
    }

    private static Page<Warehouse> pageOf(Warehouse... rows) {
        Page<Warehouse> page = new Page<>(1, 500);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
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

    @Test
    void upsertWarehouse_setsManager_whenPositiveId() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = warehouseService.upsertWarehouse("WH-001", "主仓库", "addr", "ACTIVE", 42L);

        assertEquals(42L, dto.managerUserId());
    }

    @Test
    void upsertWarehouse_clearsManager_whenZero() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(9L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = warehouseService.upsertWarehouse("WH-001", "主仓库", "addr", "ACTIVE", 0L);

        assertNull(dto.managerUserId());
    }

    @Test
    void upsertWarehouse_keepsManager_whenOmitted() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(9L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = warehouseService.upsertWarehouse("WH-001", "主仓库", "addr", "ACTIVE", null);

        assertEquals(9L, dto.managerUserId());
    }

    private static Warehouse existingWarehouse() {
        Warehouse existing = new Warehouse();
        existing.setWarehouseId("WH-001");
        existing.setWarehouseName("主仓库");
        existing.setStatus("ACTIVE");
        existing.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return existing;
    }

    private static WarehouseInventory inventoryLot(String skuId, String batchNo, int qty, LocalDate expiryDate) {
        WarehouseInventory lot = new WarehouseInventory();
        lot.setWarehouseId(TEST_WAREHOUSE_ID);
        lot.setSkuId(skuId);
        lot.setBatchNo(batchNo);
        lot.setExpiryDate(expiryDate);
        lot.setQuantity(qty);
        return lot;
    }
}
