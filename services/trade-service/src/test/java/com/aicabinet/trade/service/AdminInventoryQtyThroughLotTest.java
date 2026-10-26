package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertDeviceSkuInventoryRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G5 §7.24 发现修复的回归钉：admin PUT sku-inventory 的 quantity **不得直改列**——
 * 差额必须走批次账本（增=addRestock / 减=pullOff FEFO），汇总由 syncAggregateInventory 从账本回写。
 * 修复前实测：直改 quantity=99 ⇒ device_sku_inventory=99 而 device_sku_lot Σ=3（账面/账本分叉）。
 */
@ExtendWith(MockitoExtension.class)
class AdminInventoryQtyThroughLotTest {

    private static final String DEV = "EGTEST000001";
    private static final String SKU = "100099";

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private SkuSceneLimitService skuSceneLimitService;

    private AdminDashboardService service;
    private DeviceSkuInventory inv;

    @BeforeEach
    void setUp() {
        service = new AdminDashboardService(
                deviceRepository, null, null, null, null, null, null, null, skuCatalogRepository, auditService, null, null, null, null, null, null, merchantScopeService, inventoryRepository, null, null, inventoryLotService, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, skuSceneLimitService);
        lenient().when(deviceRepository.selectById(DEV)).thenReturn(new DeviceInfo());
        lenient().when(skuCatalogRepository.selectById(SKU)).thenReturn(new com.aicabinet.trade.domain.SkuCatalog());
        inv = new DeviceSkuInventory();
        inv.setId(new com.aicabinet.trade.domain.DeviceSkuInventoryId(DEV, SKU));
        inv.setQuantity(3);
        inv.setCapacity(8);
        inv.setLowThreshold(2);
        lenient().when(inventoryRepository.selectOne(any())).thenAnswer(x -> inv);
        // 模拟「流水后 syncAggregateInventory 回写」：addRestock/pullOff 调整账本量并回写 inv.quantity
        lenient().doAnswer(x -> {
            InventoryLotService.RestockCommand cmd = x.getArgument(2);
            inv.setQuantity(inv.getQuantity() + cmd.quantity());
            return null;
        }).when(inventoryLotService).addRestock(anyString(), anyString(), any());
        lenient().doAnswer(x -> {
            int qty = x.getArgument(3);
            inv.setQuantity(inv.getQuantity() - qty);
            return null;
        }).when(inventoryLotService).pullOff(anyString(), anyString(), isNull(), anyInt(), any(), anyString());
    }

    @Test
    void quantityIncrease_goesThroughLotRestock() {
        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 10, 8, 2));

        // 增量 7 走 addRestock（不直改）
        verify(inventoryLotService).addRestock(eq(DEV), eq(SKU), any());
        verify(inventoryLotService, never()).pullOff(anyString(), anyString(), any(), anyInt(), any(), anyString());
    }

    @Test
    void quantityDecrease_goesThroughFefoPullOff() {
        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 1, 8, 2));

        // 减量 2 走 pullOff（FEFO，batchNo=null）
        verify(inventoryLotService).pullOff(eq(DEV), eq(SKU), isNull(), eq(2), any(), anyString());
        verify(inventoryLotService, never()).addRestock(anyString(), anyString(), any());
    }

    @Test
    void quantityUnchanged_noLedgerCall() {
        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 3, 8, 2));

        verify(inventoryLotService, never()).addRestock(anyString(), anyString(), any());
        verify(inventoryLotService, never()).pullOff(anyString(), anyString(), any(), anyInt(), any(), anyString());
    }

    @Test
    void capacityOnly_noLedgerCall() {
        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, null, 8, 5));

        verify(inventoryLotService, never()).addRestock(anyString(), anyString(), any());
        assertEquals(3, inv.getQuantity());
    }

    @Test
    void restockCommandCarriesRefAndBatch() {
        service.upsertDeviceSkuInventory(7L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 5, 8, 2));

        var cmd = org.mockito.ArgumentCaptor.forClass(InventoryLotService.RestockCommand.class);
        verify(inventoryLotService).addRestock(eq(DEV), eq(SKU), cmd.capture());
        assertTrue(cmd.getValue().batchNo().startsWith("B-ADMIN-"));
        assertEquals(2, cmd.getValue().quantity());
        assertEquals(7L, cmd.getValue().ref().operatorId());
        assertEquals("ADMIN_ADJUST", cmd.getValue().ref().refType());
    }

    // ── 分叉行：该柜已有批次账本，但本 SKU 一条没建（2026-10-26 雪碧实测） ──────────

    /** 把 addRestock 改造成「空账本建账」语义：账面=Σ账本=本笔全量，而非在账面基础上累加。 */
    private void stubRestockAsSeedingEmptyLedger() {
        org.mockito.Mockito.reset(inventoryLotService);
        lenient().when(inventoryLotService.lotLedgerSkuIds(DEV))
                .thenReturn(java.util.Set.of("OTHER-SKU"));
        doAnswer(x -> {
            InventoryLotService.RestockCommand cmd = x.getArgument(2);
            inv.setQuantity(cmd.quantity());
            return null;
        }).when(inventoryLotService).addRestock(anyString(), anyString(), any());
    }

    @Test
    void divergedSkuWithoutLots_restocksFullRequestNotDeltaVsBook() {
        stubRestockAsSeedingEmptyLedger();

        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 5, 8, 2));

        // 账面 3 进不了差额口径：整笔 5 建账，否则账本永远少账面那一截、卡片回显不动
        var cmd = org.mockito.ArgumentCaptor.forClass(InventoryLotService.RestockCommand.class);
        verify(inventoryLotService).addRestock(eq(DEV), eq(SKU), cmd.capture());
        assertEquals(5, cmd.getValue().quantity());
        assertEquals(5, inv.getQuantity());
    }

    @Test
    void divergedSkuWithoutLots_requestZeroReconcilesBookToEmptyLedger() {
        lenient().when(inventoryLotService.lotLedgerSkuIds(DEV))
                .thenReturn(java.util.Set.of("OTHER-SKU"));

        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 0, 8, 2));

        // 空账本无批次可 FEFO 扣 ⇒ 既不能 pullOff（会 409）也不能静默留着账面 3
        verify(inventoryLotService, never()).pullOff(anyString(), anyString(), any(), anyInt(), any(), anyString());
        verify(inventoryLotService, never()).addRestock(anyString(), anyString(), any());
        verify(inventoryLotService).syncAggregateInventory(DEV, SKU);
    }

    @Test
    void ledgeredSku_stillDeltasAgainstBook() {
        lenient().when(inventoryLotService.lotLedgerSkuIds(DEV)).thenReturn(java.util.Set.of(SKU));

        service.upsertDeviceSkuInventory(1L, DEV,
                new UpsertDeviceSkuInventoryRequest(SKU, 5, 8, 2));

        // 已建账 ⇒ 账面=Σ账本（sync 维持不变式），仍按差额 2 补，不得整笔重记
        var cmd = org.mockito.ArgumentCaptor.forClass(InventoryLotService.RestockCommand.class);
        verify(inventoryLotService).addRestock(eq(DEV), eq(SKU), cmd.capture());
        assertEquals(2, cmd.getValue().quantity());
    }
}
