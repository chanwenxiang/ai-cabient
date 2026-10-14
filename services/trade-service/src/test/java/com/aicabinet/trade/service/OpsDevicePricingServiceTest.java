package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicabinet.common.dto.UpdateDeviceSkuPriceRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 单柜定价（V350，CB-037）核心不变量：
 * 1. 限价 fail-closed：越界 400，且限价规则与商户端同源（SkuPriceBounds）。
 * 2. 「停用」保留预设价行（enabled=false），「恢复基准」删行——两个动作语义不同。
 * 3. 乐观锁：已有覆盖价必须带 expectedVersion。
 */
@ExtendWith(MockitoExtension.class)
class OpsDevicePricingServiceTest {

    @Mock DeviceSkuPriceMapper priceRepository;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock PermissionService permissionService;
    @Mock AdminAuditService auditService;
    @Mock InventoryLotService inventoryLotService;
    @Mock DistributedLockService distributedLockService;
    @Mock MerchantSkuPricingService pricingService;

    private OpsDevicePricingService service;

    @BeforeEach
    void setUp() {
        service = new OpsDevicePricingService(
                priceRepository, inventoryRepository, skuCatalogRepository, deviceRepository,
                permissionService, auditService, inventoryLotService, distributedLockService, pricingService);
        // requirePermission/appendLog/unlock 为 void 方法：Mockito 默认 doNothing，无需 stub
        lenient().when(distributedLockService.tryLock(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    private DeviceInfo device() {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId("CAB-001");
        d.setDeviceName("测试柜");
        return d;
    }

    private SkuCatalog sku(int basePrice) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId("10001");
        s.setSkuName("可乐");
        s.setPriceCents(basePrice);
        return s;
    }

    @Test
    void updatePrice_rejectsBelowExplicitMinPrice() {
        SkuCatalog s = sku(500);
        s.setMinPriceCents(300); // 显式最低限价
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device()));
        when(skuCatalogRepository.findById("10001")).thenReturn(Optional.of(s));
        when(priceRepository.findByDeviceIdAndSkuIdForUpdate("CAB-001", "10001"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updatePricing(1L, "CAB-001", "10001",
                        new UpdateDeviceSkuPriceRequest(200, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(priceRepository, never()).save(any());
    }

    @Test
    void updatePrice_allowsBelowCost_whenNoMinPriceSet() {
        // 用户拍板（CB-037）：无显式最低限价即可打折（可低于成本），仅要求 >0
        SkuCatalog s = sku(500);
        s.setPurchaseCostCents(400); // 成本 4 元，目标价 3 元 < 成本：放行
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device()));
        when(skuCatalogRepository.findById("10001")).thenReturn(Optional.of(s));
        when(priceRepository.findByDeviceIdAndSkuIdForUpdate("CAB-001", "10001"))
                .thenReturn(Optional.empty());
        when(priceRepository.findByDeviceIdAndSkuId("CAB-001", "10001")).thenReturn(Optional.empty());
        when(inventoryRepository.findByIdDeviceId("CAB-001")).thenReturn(java.util.List.of());

        service.updatePricing(1L, "CAB-001", "10001",
                new UpdateDeviceSkuPriceRequest(300, null, null));

        org.mockito.ArgumentCaptor<DeviceSkuPrice> captor =
                org.mockito.ArgumentCaptor.forClass(DeviceSkuPrice.class);
        verify(priceRepository).save(captor.capture());
        assertEquals(300, captor.getValue().getPriceCents());
        assertTrue(captor.getValue().isEnabled(), "新建覆盖价默认启用");
    }

    @Test
    void disableKeepsRowWhileResetDeletesRow() {
        SkuCatalog s = sku(500);
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device()));
        when(skuCatalogRepository.findById("10001")).thenReturn(Optional.of(s));
        DeviceSkuPrice existing = new DeviceSkuPrice();
        existing.setDeviceId("CAB-001");
        existing.setSkuId("10001");
        existing.setPriceCents(350);
        existing.setEnabled(true);
        existing.setVersion(3);
        when(priceRepository.findByDeviceIdAndSkuIdForUpdate("CAB-001", "10001"))
                .thenReturn(Optional.of(existing));
        when(inventoryRepository.findByIdDeviceId("CAB-001")).thenReturn(java.util.List.of());
        when(priceRepository.findByDeviceIdAndSkuId("CAB-001", "10001")).thenReturn(Optional.of(existing));

        // 停用：行保留、enabled=false
        service.updatePricing(1L, "CAB-001", "10001",
                new UpdateDeviceSkuPriceRequest(350, false, 3L));
        org.mockito.ArgumentCaptor<DeviceSkuPrice> captor =
                org.mockito.ArgumentCaptor.forClass(DeviceSkuPrice.class);
        verify(priceRepository).save(captor.capture());
        assertFalse(captor.getValue().isEnabled());
        verify(priceRepository, never()).delete((DeviceSkuPrice) any());

        // 恢复基准：删行
        service.updatePricing(1L, "CAB-001", "10001",
                new UpdateDeviceSkuPriceRequest(null, null, 3L));
        verify(priceRepository).delete((DeviceSkuPrice) any());
    }

    @Test
    void updatePrice_requiresExpectedVersionWhenOverrideExists() {
        SkuCatalog s = sku(500);
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device()));
        when(skuCatalogRepository.findById("10001")).thenReturn(Optional.of(s));
        DeviceSkuPrice existing = new DeviceSkuPrice();
        existing.setDeviceId("CAB-001");
        existing.setSkuId("10001");
        existing.setPriceCents(350);
        existing.setEnabled(true);
        existing.setVersion(3);
        when(priceRepository.findByDeviceIdAndSkuIdForUpdate("CAB-001", "10001"))
                .thenReturn(Optional.of(existing));

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.updatePricing(1L, "CAB-001", "10001",
                        new UpdateDeviceSkuPriceRequest(300, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        ResponseStatusException mismatched = assertThrows(ResponseStatusException.class,
                () -> service.updatePricing(1L, "CAB-001", "10001",
                        new UpdateDeviceSkuPriceRequest(300, null, 99L)));
        assertEquals(HttpStatus.CONFLICT, mismatched.getStatusCode());
    }
}
