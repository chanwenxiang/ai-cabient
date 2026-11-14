package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NearbyDeviceDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NearbyDeviceServiceTest {

    @Mock DeviceInfoMapper deviceRepository;
    @Mock DeviceCatalogService deviceCatalogService;
    @Mock DeviceValidationService deviceValidationService;
    @InjectMocks NearbyDeviceService service;

    @Test
    void listNearby_sortsByDistance() {
        DeviceInfo near = device("CAB-NEAR", 31.2310, 121.4740);
        DeviceInfo far = device("CAB-FAR", 31.2500, 121.5000);
        when(deviceRepository.findNearbyCandidates(anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(far, near));
        // 2026-11-14：可用性改成批量口径（availableByDevices），逐台 getDeviceStatus 不再被调用。
        // 语义等价由 NearbyAvailabilityBatchSqlIT（真 PG）守着，这里只验「批量值 -> DTO 字段」的映射。
        when(deviceValidationService.availableByDevices(anyCollection())).thenReturn(
                java.util.Map.of("CAB-NEAR", true, "CAB-FAR", false));
        when(deviceCatalogService.listProducts(anyString())).thenReturn(List.of());

        List<NearbyDeviceDto> list = service.listNearby(31.2304, 121.4737, 10, 10);
        assertEquals(2, list.size());
        assertTrue(list.stream().filter(d -> "CAB-NEAR".equals(d.deviceId())).findFirst().orElseThrow().available(),
                "批量判可售 ⇒ DTO available 必须为 true");
        assertFalse(list.stream().filter(d -> "CAB-FAR".equals(d.deviceId())).findFirst().orElseThrow().available(),
                "批量判不可售 ⇒ DTO available 必须为 false（映射反了会把占用柜标成可售）");
        assertEquals("CAB-NEAR", list.get(0).deviceId());
        assertTrue(list.get(0).distanceMeters() < list.get(1).distanceMeters());
        // R20 P2-16：候选必须来自 SQL 粗筛，不能再全表载入内存
        verify(deviceRepository, never()).findAllOrderByDeviceIdAsc();
    }

    /**
     * R20 P2-16：矩形框下推参数必须真的覆盖半径。
     *
     * <p>1 度纬度 ≈ R·π/180 = 111.19km ⇒ 10km 半径的**半跨度** ≈ 0.0899 度，
     * 矩形总跨度（±半径）≈ 0.1799 度；经度 1 度实际距离随纬度收缩（×cos φ，上海 φ≈31.23° ⇒ 0.855）
     * ⇒ 经度总跨度应 ≈ 0.2104 度，比纬度跨度**更大**。反了（经度跨度更小）说明乘除写反，
     * 会漏掉东西向的柜机。
     */
    @Test
    void listNearby_pushesBoundingBoxDownToSql() {
        when(deviceRepository.findNearbyCandidates(anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of());

        service.listNearby(31.2304, 121.4737, 10, 10);

        org.mockito.ArgumentCaptor<Double> minLat = org.mockito.ArgumentCaptor.forClass(Double.class);
        org.mockito.ArgumentCaptor<Double> maxLat = org.mockito.ArgumentCaptor.forClass(Double.class);
        org.mockito.ArgumentCaptor<Double> minLon = org.mockito.ArgumentCaptor.forClass(Double.class);
        org.mockito.ArgumentCaptor<Double> maxLon = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(deviceRepository).findNearbyCandidates(
                minLat.capture(), maxLat.capture(), minLon.capture(), maxLon.capture());

        double latSpan = maxLat.getValue() - minLat.getValue();
        double lonSpan = maxLon.getValue() - minLon.getValue();
        assertEquals(0.1799, latSpan, 0.002, "10km 半径的纬度总跨度 ≈ 2×10/111.19 度");
        assertTrue(lonSpan > latSpan, "同一半径下经度跨度必须大于纬度跨度（cos φ < 1）");
        assertEquals(0.2104, lonSpan, 0.003, "经度跨度 ≈ 纬度跨度 / cos(31.23°)");
        assertEquals(31.2304, (minLat.getValue() + maxLat.getValue()) / 2, 1e-9, "矩形以查询点为心");
        assertEquals(121.4737, (minLon.getValue() + maxLon.getValue()) / 2, 1e-9);
    }

    private static DeviceInfo device(String id, double lat, double lng) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setDeviceName(id);
        d.setLatitude(lat);
        d.setLongitude(lng);
        d.setOnlineStatus("ONLINE");
        d.setLifecycleStatus("DEPLOYED");
        d.setAddress("demo");
        return d;
    }

    /**
     * 2026-11-14：预览改成批量（previewByDevices）。本例只钉「批量值 → DTO 三个字段」的映射：
     * sellableSkuCount / sellableItemCount / previewSkus 若接错位置，附近页会显示错的件数或商品。
     * 口径等价性（含禁用货道、覆盖价）由 NearbyPreviewBatchSqlIT 在真库上守。
     */
    @Test
    void listNearby_mapsBatchPreviewIntoDto() {
        DeviceInfo one = device("CAB-PRV", 31.2304, 121.4737);
        when(deviceRepository.findNearbyCandidates(anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(one));
        when(deviceValidationService.availableByDevices(anyCollection()))
                .thenReturn(java.util.Map.of("CAB-PRV", true));
        when(deviceCatalogService.previewByDevices(anyCollection(), any(), anyInt())).thenReturn(
                java.util.Map.of("CAB-PRV", new DeviceCatalogService.DevicePreview(
                        7, 2,
                        List.of(new com.aicabinet.common.dto.NearbyDeviceDto.PreviewSku("S-1", "甲", 4, 350)))));

        NearbyDeviceDto dto = service.listNearby(31.2304, 121.4737, 5, 10).get(0);

        assertEquals(7, dto.sellableItemCount(), "总件数必须取批量 itemCount");
        assertEquals(2, dto.sellableSkuCount(), "SKU 数必须取批量 skuCount");
        assertEquals(1, dto.previewSkus().size());
        assertEquals(350, dto.previewSkus().get(0).unitPriceCents(), "预览单价必须是批量算出的会员价");
        org.mockito.Mockito.verify(deviceCatalogService, org.mockito.Mockito.never())
                .listProducts(org.mockito.ArgumentMatchers.anyString());
    }
}
