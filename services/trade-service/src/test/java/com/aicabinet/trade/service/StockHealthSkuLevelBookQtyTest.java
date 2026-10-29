package com.aicabinet.trade.service;

import com.aicabinet.common.dto.StockHealthRowDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceLifecycleEventMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * D28-A6 回归钉：库存健康的「账面量」必须按 <b>SKU 级</b>判据取，与补货建议量同口径。
 *
 * <p>设备级判据（整机有没有任意 lot 行）会把「同柜从未进过账本的 SKU」读成 0 ⇒ 假缺货，
 * 与建议量/商户端库存页的数字当面打架。本类造一台「只有 SKU-A 建过批次账」的柜：
 * SKU-A 卖光 = 0（事实），SKU-B 从未进账本但账面 5 ⇒ 必须读 5。
 */
@ExtendWith(MockitoExtension.class)
class StockHealthSkuLevelBookQtyTest {

    private static final String DEV = "EGTEST000001";
    private static final String SKU_LEDGERED = "100001";
    private static final String SKU_NEVER_LEDGERED = "100002";

    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private DeviceLifecycleEventMapper lifecycleEventMapper;
    @Mock private DeviceSkuInventoryMapper inventoryMapper;
    @Mock private DeviceSkuLotMapper lotMapper;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private DeviceAssetService service;

    @BeforeEach
    void setUp() {
        service = new DeviceAssetService(deviceInfoMapper, lifecycleEventMapper, inventoryMapper, lotMapper,
                inventoryLotService, skuCatalogMapper, merchantMapper, merchantScopeService, permissionService,
                auditService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEV);
        device.setDeviceName("测试柜");
        device.setMerchantId("MCH-A");
        device.setRouteCode("R-1");
        device.setLifecycleStatus("DEPLOYED");
        lenient().when(deviceInfoMapper.findAll()).thenReturn(List.of(device));
        lenient().when(skuCatalogMapper.findAll()).thenReturn(List.of(sku(SKU_LEDGERED), sku(SKU_NEVER_LEDGERED)));
        lenient().when(merchantScopeService.allowedDeviceIds(anyLong())).thenReturn(null);

        // 只有 SKU_A 进过批次账本，且已卖光（账本可售 0）
        lenient().when(inventoryLotService.lotLedgerSkuIds(DEV)).thenReturn(Set.of(SKU_LEDGERED));
        lenient().when(inventoryLotService.sellableQtyBySku(DEV)).thenReturn(Map.of(SKU_LEDGERED, 0));
    }

    private static SkuCatalog sku(String skuId) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName("商品" + skuId);
        return s;
    }

    private static DeviceSkuInventory inv(String skuId, int qty, int lowThreshold) {
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setId(new DeviceSkuInventoryId(DEV, skuId));
        row.setQuantity(qty);
        row.setCapacity(10);
        row.setLowThreshold(lowThreshold);
        return row;
    }

    private List<StockHealthRowDto> health() {
        return service.stockHealth(1L, "ALL", null, null, null, DEV);
    }

    @Test
    void ledgeredSkuSoldOut_readsZero_asStockout() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inv(SKU_LEDGERED, 3, 2)));

        List<StockHealthRowDto> rows = health();

        // 账面列还写着 3，但账本可售 0 ⇒ 缺货（事实），不得回退账面
        StockHealthRowDto row = rows.stream()
                .filter(r -> SKU_LEDGERED.equals(r.skuId()))
                .findFirst().orElseThrow();
        assertEquals(0, row.quantity());
        assertEquals("STOCKOUT", row.dimension());
    }

    @Test
    void skuNeverEnteredLedger_readsBookQty_notZero() {
        // SKU-B 账面 5 > 低库存阈值 3 ⇒ 健康页不该出现这一行（旧设备级判据会读成 0 = 假缺货）
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                inv(SKU_LEDGERED, 0, 2),
                inv(SKU_NEVER_LEDGERED, 5, 3)));

        List<StockHealthRowDto> rows = health();

        assertTrue(rows.stream().noneMatch(r -> SKU_NEVER_LEDGERED.equals(r.skuId())),
                "未进账本的 SKU 必须按 device_sku_inventory 账面判，5 件不算缺货/低库存");
    }

    @Test
    void skuNeverEnteredLedger_atLowThreshold_readsBookQty_asLow() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                inv(SKU_LEDGERED, 0, 2),
                inv(SKU_NEVER_LEDGERED, 2, 3)));

        List<StockHealthRowDto> rows = health();

        StockHealthRowDto low = rows.stream()
                .filter(r -> SKU_NEVER_LEDGERED.equals(r.skuId()))
                .findFirst().orElseThrow();
        // 2 ≤ 阈值 3 ⇒ 低库存（不是缺货），且数量显示 2 不是 0
        assertEquals("LOW", low.dimension());
        assertEquals(2, low.quantity());
    }

    @Test
    void deviceWithoutAnyLotRow_readsBookQtyForAllSkus() {
        when(inventoryLotService.lotLedgerSkuIds(anyString())).thenReturn(Set.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inv(SKU_NEVER_LEDGERED, 1, 3)));

        List<StockHealthRowDto> rows = health();

        StockHealthRowDto row = rows.stream()
                .filter(r -> SKU_NEVER_LEDGERED.equals(r.skuId()))
                .findFirst().orElseThrow();
        assertEquals(1, row.quantity());
    }
}
