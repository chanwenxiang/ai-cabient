package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSkuPricingDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * D28-A6：商户端定价页的「库存」列按 <b>SKU 级</b>判据取，与库存页/补货建议量同口径。
 *
 * <p>旧实现用设备级 {@code deviceUsesLotLedger}：柜里只要有一个 SKU 建过批次账，
 * 其余从未进账本的 SKU 全读成 0（假缺货）——商户在定价页看到 0、在库存页看到有货。
 */
@ExtendWith(MockitoExtension.class)
class MerchantSkuPricingSellableQtyTest {

    private static final String DEV = "CAB-001";
    private static final String SKU_LEDGERED = "100013";
    private static final String SKU_BOOK_ONLY = "100014";

    @Mock private DeviceSkuPriceMapper priceRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private AdminAuditService auditService;
    @Mock private AdminAuditLogMapper auditLogRepository;
    @Mock private MerchantSelfServiceGate merchantSelfServiceGate;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;

    private MerchantSkuPricingService service;

    @BeforeEach
    void setUp() {
        service = new MerchantSkuPricingService(priceRepository, inventoryRepository, skuCatalogRepository,
                deviceRepository, permissionService, merchantPortalGuard, auditService, auditLogRepository,
                merchantSelfServiceGate, merchantFeaturePackService, inventoryLotService,
                distributedLockService, systemConfigService, null);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEV);
        device.setDeviceName("测试柜");
        lenient().when(merchantFeaturePackService.allowedDeviceIdsForPack(anyLong(), any())).thenReturn(null);
        lenient().when(merchantFeaturePackService.allowedDevicesForPack(anyLong(), any())).thenReturn(List.of(device));
        lenient().when(priceRepository.findByIdDeviceIdIn(any())).thenReturn(List.of());
        lenient().when(inventoryRepository.findByIdDeviceId(DEV)).thenReturn(List.of(
                inv(SKU_LEDGERED, 4), inv(SKU_BOOK_ONLY, 5)));
        lenient().when(skuCatalogRepository.findAllById(any()))
                .thenReturn(List.of(sku(SKU_LEDGERED), sku(SKU_BOOK_ONLY)));
        // 只有 100013 建过批次账且卖光
        lenient().when(inventoryLotService.lotLedgerSkuIds(DEV)).thenReturn(Set.of(SKU_LEDGERED));
        lenient().when(inventoryLotService.sellableQtyBySku(DEV)).thenReturn(Map.of(SKU_LEDGERED, 0));
    }

    private static DeviceSkuInventory inv(String skuId, int qty) {
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setId(new DeviceSkuInventoryId(DEV, skuId));
        row.setQuantity(qty);
        row.setCapacity(10);
        return row;
    }

    private static SkuCatalog sku(String skuId) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName("商品" + skuId);
        s.setPriceCents(350);
        s.setStatus("ACTIVE");
        return s;
    }

    private static int qtyOf(List<MerchantSkuPricingDto> rows, String skuId) {
        return rows.stream().filter(r -> skuId.equals(r.skuId())).findFirst().orElseThrow().inventoryQty();
    }

    @Test
    void ledgeredSkuSoldOut_readsZero() {
        List<MerchantSkuPricingDto> rows = service.listPricing(9L, DEV);
        assertEquals(0, qtyOf(rows, SKU_LEDGERED));
    }

    @Test
    void skuNeverEnteredLedger_readsBookQty() {
        List<MerchantSkuPricingDto> rows = service.listPricing(9L, DEV);
        assertEquals(5, qtyOf(rows, SKU_BOOK_ONLY), "未进账本的 SKU 必须回退账面 5，不得读成 0");
    }

    @Test
    void deviceWithoutAnyLotRow_readsBookQty() {
        when(inventoryLotService.lotLedgerSkuIds(DEV)).thenReturn(Set.of());
        List<MerchantSkuPricingDto> rows = service.listPricing(9L, DEV);
        assertEquals(4, qtyOf(rows, SKU_LEDGERED));
        assertEquals(5, qtyOf(rows, SKU_BOOK_ONLY));
    }
}
