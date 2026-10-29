package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantPrepPreviewGroupDto;
import com.aicabinet.common.dto.MerchantPrepPreviewItemDto;
import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A 方案「手动生成备货单」预览（2026-10-29）：逐柜 fillToPar 建议 → 按柜分组预览组。
 * 钉住四件事：范围=FIELD 包、口径走 {@code suggestForDevice(dev, true)}（补满），
 * 无缺口柜不返回，目录缺失回落 skuId/「支」/packQty=1。
 */
@ExtendWith(MockitoExtension.class)
class MerchantPrepPreviewTest {

    @Mock private PermissionService permissionService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private ReplenishmentService replenishmentService;
    @Mock private WarehouseService warehouseService;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;

    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new MerchantReplenishmentService(
                permissionService, merchantFeaturePackService, merchantPortalGuard, replenishmentService,
                null, warehouseService, null, deviceRepository, skuCatalogRepository,
                null, null, null,
                null, null, null, null);
    }

    private ReplenishmentSuggestDto suggest(String skuId, int currentQty, int capacity,
                                            int inTransit, int suggestQty) {
        return new ReplenishmentSuggestDto("DEV-1", skuId, currentQty, capacity,
                0, suggestQty, inTransit, 0, 0, 0, "PAR");
    }

    @Test
    void preview_buildsGroupsFromFillToParSuggestions() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("DEV-1"));
        when(warehouseService.suggestForDevice(eq("DEV-1"), eq(true))).thenReturn(List.of(
                suggest("SKU-B", 2, 10, 1, 7),
                suggest("SKU-A", 0, 8, 0, 8)));
        SkuCatalog b = new SkuCatalog();
        b.setSkuId("SKU-B");
        b.setSkuName("冰红茶");
        b.setUnit("瓶");
        b.setPackQty(6);
        when(skuCatalogRepository.selectList(any())).thenReturn(List.of(b));
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("DEV-1");
        device.setDeviceName("大堂柜");
        when(deviceRepository.findById("DEV-1")).thenReturn(java.util.Optional.of(device));

        List<MerchantPrepPreviewGroupDto> out = service.prepPreview(1L);

        assertEquals(1, out.size());
        MerchantPrepPreviewGroupDto group = out.get(0);
        assertEquals("DEV-1", group.deviceId());
        assertEquals("大堂柜", group.deviceName());
        assertEquals(2, group.items().size());
        // 建议量降序：SKU-A(8) 在 SKU-B(7) 前
        MerchantPrepPreviewItemDto first = group.items().get(0);
        assertEquals("SKU-A", first.skuId());
        assertEquals(8, first.suggestQty());
        MerchantPrepPreviewItemDto second = group.items().get(1);
        assertEquals("SKU-B", second.skuId());
        assertEquals("冰红茶", second.skuName());
        assertEquals("瓶", second.unit());
        assertEquals(6, second.packQty());
        assertEquals(1, second.inTransitQty());
    }

    @Test
    void preview_emptyFieldScope_returnsEmptyWithoutSuggestCalls() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of());
        assertTrue(service.prepPreview(1L).isEmpty());
        verify(warehouseService, never()).suggestForDevice(any(), eq(true));
    }

    @Test
    void preview_deviceWithoutGap_isOmitted() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("DEV-1", "DEV-2"));
        when(warehouseService.suggestForDevice(eq("DEV-1"), eq(true))).thenReturn(List.of());
        when(warehouseService.suggestForDevice(eq("DEV-2"), eq(true))).thenReturn(List.of(
                suggest("SKU-A", 1, 5, 0, 4)));
        when(skuCatalogRepository.selectList(any())).thenReturn(List.of());
        when(deviceRepository.findById("DEV-2")).thenReturn(java.util.Optional.empty());

        List<MerchantPrepPreviewGroupDto> out = service.prepPreview(1L);

        assertEquals(1, out.size());
        assertEquals("DEV-2", out.get(0).deviceId());
        // 目录缺失回落：skuId 兜底名 / 「支」 / packQty=1（与备货清单同口径）
        MerchantPrepPreviewItemDto item = out.get(0).items().get(0);
        assertEquals("SKU-A", item.skuName());
        assertEquals("支", item.unit());
        assertEquals(1, item.packQty());
    }

    @Test
    void preview_zeroSuggestRows_filteredOut() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("DEV-1"));
        when(warehouseService.suggestForDevice(eq("DEV-1"), eq(true))).thenReturn(List.of(
                suggest("SKU-A", 5, 5, 0, 0),
                suggest("SKU-B", 1, 6, 2, 3)));
        when(skuCatalogRepository.selectList(any())).thenReturn(List.of());
        when(deviceRepository.findById("DEV-1")).thenReturn(java.util.Optional.empty());

        List<MerchantPrepPreviewGroupDto> out = service.prepPreview(1L);

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).items().size());
        assertEquals("SKU-B", out.get(0).items().get(0).skuId());
    }

    @Test
    void preview_requiresViewPermission() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(1L, "merchant:replenishment:view");
        assertThrows(ResponseStatusException.class, () -> service.prepPreview(1L));
        verify(merchantFeaturePackService, never()).allowedDeviceIdsForPack(any(), any());
    }
}
