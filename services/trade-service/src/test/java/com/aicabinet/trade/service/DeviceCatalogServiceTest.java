package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费者商品目录的可见性口径。
 *
 * <p>背景（线上实测）：运营在设备详情里把货道 A1 的「启用」关掉并保存，库里的
 * {@code device_slot.enabled} 确实变成 false，但消费者小程序照样显示该货道的商品 ——
 * 因为 {@code listProducts()} 走的是「按 sku 汇总批次」的老查询，那条 SQL 里
 * 根本没有货道这个概念，启用开关自然不生效。
 *
 * <p>这里的核心判据是**口径分离**：消费者链路只能调用
 * {@code sumSellableBySkuOnEnabledSlots}，绝不能调用运营口径的 {@code sumSellableBySku}
 * （后者要算物理库存全量，拿它当消费者可见量就等于开关失效）。
 */
@ExtendWith(MockitoExtension.class)
class DeviceCatalogServiceTest {

    private static final String DEVICE_ID = "330449777078";

    @Mock
    private DeviceSkuInventoryMapper inventoryRepository;
    @Mock
    private DeviceSkuLotMapper lotRepository;
    @Mock
    private SkuCatalogMapper skuCatalogRepository;
    @Mock
    private MerchantSkuPricingService skuPricingService;

    private DeviceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new DeviceCatalogService(
                inventoryRepository, lotRepository, skuCatalogRepository, skuPricingService);
    }

    @Test
    @DisplayName("禁用货道上的 SKU 对消费者不可见（回归：禁用后小程序仍显示）")
    void listProducts_excludesSkuWhoseSlotIsDisabled() {
        // 库里有批次账本 ⇒ 走批次路径
        when(lotRepository.findByDeviceId(DEVICE_ID)).thenReturn(List.of(new DeviceSkuLot()));
        // 消费者口径：可口可乐所在货道已禁用 ⇒ 汇总里根本没有它
        when(lotRepository.sumSellableBySkuOnEnabledSlots(DEVICE_ID))
                .thenReturn(List.<Object[]>of(row("SKU-SODA-001", 8)));
        // SKU 目录照旧能查到两个 SKU —— 让「禁用那个」只可能因为口径被排除，
        // 而不是因为查不到 SKU 元数据（否则判据没有判别力）。
        when(skuCatalogRepository.findAllById(any()))
                .thenReturn(List.of(sku("SKU-DEMO-001", "可口可乐 330ml"), sku("SKU-SODA-001", "雪碧 500ml")));
        when(skuPricingService.loadPromoPolicy()).thenReturn(null);
        when(skuPricingService.resolveUnitPriceCents(anyString(), any(SkuCatalog.class),
                nullable(PricingPromoPolicy.class))).thenReturn(350);

        List<DeviceProductDto> out = service.listProducts(DEVICE_ID);

        assertEquals(List.of("SKU-SODA-001"), out.stream().map(DeviceProductDto::skuId).toList(),
                "禁用货道上的 SKU-DEMO-001 不该出现在消费者商品列表里");
        // 口径分离：消费者链路绝不允许用运营口径（否则开关失效）
        verify(lotRepository, never()).sumSellableBySku(anyString());
    }

    @Test
    @DisplayName("货道全被禁用时，该柜机商品列表为空（而不是回退成物理库存）")
    void listProducts_allSlotsDisabled_returnsEmpty() {
        when(lotRepository.findByDeviceId(DEVICE_ID)).thenReturn(List.of(new DeviceSkuLot()));
        when(lotRepository.sumSellableBySkuOnEnabledSlots(DEVICE_ID)).thenReturn(List.of());

        assertTrue(service.listProducts(DEVICE_ID).isEmpty());
        verify(lotRepository, never()).sumSellableBySku(anyString());
    }

    @Test
    @DisplayName("无批次账本时回退到 device_sku_inventory（旧数据兼容路径不受影响）")
    void listProducts_fallsBackToInventoryWhenNoLots() {
        when(lotRepository.findByDeviceId(DEVICE_ID)).thenReturn(List.of());
        when(inventoryRepository.findByIdDeviceId(DEVICE_ID))
                .thenReturn(List.of(inventory("SKU-WATER-001", 12)));
        when(skuCatalogRepository.findAllById(any()))
                .thenReturn(List.of(sku("SKU-WATER-001", "矿泉水 550ml")));
        when(skuPricingService.loadPromoPolicy()).thenReturn(null);
        when(skuPricingService.resolveUnitPriceCents(anyString(), any(SkuCatalog.class),
                nullable(PricingPromoPolicy.class))).thenReturn(200);

        List<DeviceProductDto> out = service.listProducts(DEVICE_ID);

        assertEquals(List.of("SKU-WATER-001"), out.stream().map(DeviceProductDto::skuId).toList());
        verify(lotRepository, never()).sumSellableBySku(anyString());
    }

    @Test
    @DisplayName("SKU 目录里状态非 ACTIVE 的不下发给消费者")
    void listProducts_skipsInactiveSku() {
        when(lotRepository.findByDeviceId(DEVICE_ID)).thenReturn(List.of(new DeviceSkuLot()));
        when(lotRepository.sumSellableBySkuOnEnabledSlots(DEVICE_ID))
                .thenReturn(List.<Object[]>of(row("SKU-OFF-001", 5)));
        SkuCatalog off = sku("SKU-OFF-001", "已下架商品");
        off.setStatus("INACTIVE");
        when(skuCatalogRepository.findAllById(any())).thenReturn(List.of(off));

        assertTrue(service.listProducts(DEVICE_ID).isEmpty());
    }

    @Test
    @DisplayName("deviceId 为空 ⇒ 400，不静默返回空列表")
    void listProducts_blankDeviceId_rejected() {
        assertThrows(ResponseStatusException.class, () -> service.listProducts("  "));
    }

    private static Object[] row(String skuId, int qty) {
        return new Object[] { skuId, qty };
    }

    private static SkuCatalog sku(String skuId, String name) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName(name);
        s.setStatus("ACTIVE");
        s.setCategory("饮料");
        return s;
    }

    private static DeviceSkuInventory inventory(String skuId, int qty) {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setId(new DeviceSkuInventoryId(DEVICE_ID, skuId));
        inv.setDeviceId(DEVICE_ID);
        inv.setSkuId(skuId);
        inv.setQuantity(qty);
        return inv;
    }
}
