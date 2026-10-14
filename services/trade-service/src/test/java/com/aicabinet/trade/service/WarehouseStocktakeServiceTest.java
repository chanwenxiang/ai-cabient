package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdjustStocktakeRequest;
import com.aicabinet.common.dto.CreateStocktakeRequest;
import com.aicabinet.common.dto.UpdateStocktakeLineRequest;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.recognition.RecognitionOutcome;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseStocktakeServiceTest {

    @Mock private PermissionService permissionService;
    @Mock private WarehouseStocktakeMapper stocktakeRepository;
    @Mock private WarehouseStocktakeLineMapper lineRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private WarehouseInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseStocktakeService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseStocktakeService(permissionService, stocktakeRepository,
                lineRepository, warehouseRepository, inventoryRepository, skuCatalogRepository,
                warehouseService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(any(), eq(60L), eq(5L))).thenReturn(true);
    }

    @Test
    void create_shouldPrefillCountedInOpenMode() {
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(warehouse()));
        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001"))
                .thenReturn(List.of(inventory("SKU-A", "B1", 12)));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInMemoryLines();

        var dto = service.create(1L, new CreateStocktakeRequest("WH-001", "OPEN", null));

        assertEquals("OPEN", dto.mode());
        assertEquals(12, dto.bookQty());
        assertEquals(12, dto.countedQty());
        assertEquals(1, dto.lines().size());
        assertEquals(Integer.valueOf(12), dto.lines().get(0).countedQty());
        assertEquals("PENDING", dto.lines().get(0).status());
        // 业务单号纯数字（BizIds.nextNumeric）；禁 STK 前缀
        assertTrue(dto.stocktakeNo() != null && dto.stocktakeNo().matches("\\d+"),
                "stocktakeNo should be numeric, was: " + dto.stocktakeNo());
        verify(permissionService).requirePermission(1L, "ops:warehouse:edit");
    }

    @Test
    void create_shouldKeepCountBlankInBlindMode() {
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(warehouse()));
        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001"))
                .thenReturn(List.of(inventory("SKU-A", "B1", 12)));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInMemoryLines();

        var dto = service.create(1L, new CreateStocktakeRequest("WH-001", "BLIND", null));

        assertEquals("BLIND", dto.mode());
        assertEquals(12, dto.bookQty());
        assertEquals(0, dto.countedQty());
        assertNull(dto.lines().get(0).countedQty());
    }

    @Test
    void updateLine_shouldMarkDiffAndMoveToInProgress() {
        WarehouseStocktake st = stocktake("DRAFT");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 10, null, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var dto = service.updateLine(1L, 1L, 1L, new UpdateStocktakeLineRequest(7, null, null));

        assertEquals("DIFF", dto.status());
        assertEquals(-3, dto.diffQty());
        assertEquals("IN_PROGRESS", st.getStatus());
        assertEquals(-3, st.getDiffQty());
        assertEquals(1, st.getDiffLineCount());
    }

    @Test
    void complete_shouldRejectWhenLineNotCounted() {
        WarehouseStocktake st = stocktake("IN_PROGRESS");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 10, null, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));

        assertThrows(ResponseStatusException.class, () -> service.complete(1L, 1L));
    }

    @Test
    void complete_shouldFinalizeDiff() {
        WarehouseStocktake st = stocktake("IN_PROGRESS");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 10, 7, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var dto = service.complete(1L, 1L);

        assertEquals("COMPLETED", st.getStatus());
        assertEquals("DIFF", line.getStatus());
        assertEquals(-3, dto.diffQty());
        assertEquals(1, dto.diffLineCount());
    }

    @Test
    void adjust_shouldApplyWarehouseAdjustment() {
        WarehouseStocktake st = stocktake("COMPLETED");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 10, 7, "DIFF");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var dto = service.adjust(1L, 1L, null);

        verify(warehouseService).adjustStocktake(new WarehouseService.StocktakeAdjustCommand(
                "WH-001", new WarehouseService.LotSpec("SKU-A", "B1", null, null),
                7, 1L, 1L, line.getDiffReason()));
        assertEquals("ADJUSTED", line.getStatus());
        assertEquals("ADJUSTED", st.getStatus());
        assertEquals(0, dto.diffLineCount());
    }

    @Test
    void adjust_shouldRejectBeforeComplete() {
        WarehouseStocktake st = stocktake("DRAFT");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));

        assertThrows(ResponseStatusException.class,
                () -> service.adjust(1L, 1L, new AdjustStocktakeRequest(List.of(1L))));
    }

    @Test
    void cancel_shouldRejectNonDraft() {
        WarehouseStocktake st = stocktake("COMPLETED");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));

        assertThrows(ResponseStatusException.class, () -> service.cancel(1L, 1L));
    }

    // ── V349 分仓负责人通道：商户小程序盘点复用整仓流程 ──

    @Test
    void create_shouldBypassOpsPermForWarehouseManager() {
        Warehouse w = warehouse();
        w.setManagerUserId(77L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(w));
        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001"))
                .thenReturn(List.of(inventory("SKU-A", "B1", 12)));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInMemoryLines();

        var dto = service.create(77L, new CreateStocktakeRequest("WH-001", "OPEN", null));

        assertEquals("OPEN", dto.mode());
        verify(permissionService, org.mockito.Mockito.never())
                .requirePermission(any(), any());
    }

    @Test
    void create_shouldRejectNonManagerWithoutOpsPerm() {
        Warehouse w = warehouse();
        w.setManagerUserId(77L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(w));
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "denied"))
                .when(permissionService).requirePermission(88L, "ops:warehouse:edit");

        assertThrows(ResponseStatusException.class,
                () -> service.create(88L, new CreateStocktakeRequest("WH-001", "OPEN", null)));
    }

    @Test
    void updateLine_shouldBypassOpsPermForWarehouseManager() {
        Warehouse w = warehouse();
        w.setManagerUserId(77L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(w));
        WarehouseStocktake st = stocktake("DRAFT");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 10, null, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var dto = service.updateLine(77L, 1L, 1L, new UpdateStocktakeLineRequest(7, null, null));

        assertEquals("DIFF", dto.status());
        verify(permissionService, org.mockito.Mockito.never())
                .requirePermission(any(), any());
    }

    private static Warehouse warehouse() {
        Warehouse w = new Warehouse();
        w.setWarehouseId("WH-001");
        w.setWarehouseName("主仓");
        return w;
    }

    private static WarehouseInventory inventory(String skuId, String batchNo, int qty) {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setWarehouseId("WH-001");
        inv.setSkuId(skuId);
        inv.setBatchNo(batchNo);
        inv.setProductionDate(LocalDate.now().minusDays(3));
        inv.setExpiryDate(LocalDate.now().plusDays(60));
        inv.setQuantity(qty);
        return inv;
    }

    private static WarehouseStocktake stocktake(String status) {
        WarehouseStocktake st = new WarehouseStocktake();
        st.setStocktakeId(1L);
        st.setStocktakeNo("STK20260809-ABC123");
        st.setWarehouseId("WH-001");
        st.setMode("OPEN");
        st.setStatus(status);
        return st;
    }

    private void stubInMemoryLines() {
        List<WarehouseStocktakeLine> saved = new ArrayList<>();
        when(lineRepository.save(any())).thenAnswer(a -> {
            WarehouseStocktakeLine l = a.getArgument(0);
            saved.add(l);
            return l;
        });
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(any()))
                .thenAnswer(a -> saved);
    }

    private static WarehouseStocktakeLine line(WarehouseStocktake st, String skuId,
                                               String batchNo, int book, Integer counted,
                                               String status) {
        WarehouseStocktakeLine l = new WarehouseStocktakeLine();
        l.setLineId(1L);
        l.setStocktakeId(st.getStocktakeId());
        l.setSkuId(skuId);
        l.setBatchNo(batchNo);
        l.setBookQty(book);
        l.setCountedQty(counted);
        l.setDiffQty(counted == null ? 0 : counted - book);
        l.setStatus(status);
        return l;
    }

    @Test
    void completeAndAdjust_validatesAllCounted_thenPostsDifferencesInOneAction() {
        WarehouseStocktake st = stocktake("DRAFT");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 12, null, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(stocktakeRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInMemoryLines();
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));

        service.updateLine(1L, 1L, 1L, new UpdateStocktakeLineRequest(10, null, null));

        var dto = service.completeAndAdjust(1L, 1L);

        // 全行已盘校验 → 完成；差异（12→10）直接过账，无「已完未调」中间态
        assertEquals("ADJUSTED", dto.status());
        verify(warehouseService).adjustStocktake(org.mockito.ArgumentMatchers.argThat(
                cmd -> cmd.countedQty() == 10));
        verify(lineRepository, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeAndAdjust_rejectsWhenAnyLineUncounted() {
        WarehouseStocktake st = stocktake("DRAFT");
        WarehouseStocktakeLine line = line(st, "SKU-A", "B1", 12, null, "PENDING");
        when(stocktakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        when(lineRepository.findByStocktakeIdOrderByLineIdAsc(1L)).thenReturn(List.of(line));
        // 不录实盘 ⇒ 全行未盘，doComplete 直接 400，不落账

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.completeAndAdjust(1L, 1L));
        verify(warehouseService, org.mockito.Mockito.never())
                .adjustStocktake(org.mockito.ArgumentMatchers.any());
    }
}
