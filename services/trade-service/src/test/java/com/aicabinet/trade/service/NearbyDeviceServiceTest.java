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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
        when(deviceValidationService.getDeviceStatus(anyString())).thenAnswer(inv ->
                new com.aicabinet.common.dto.DeviceStatusDto(
                        inv.getArgument(0), "n", "ONLINE", true, true, null, null, "NONE", 0));
        when(deviceCatalogService.listProducts(anyString())).thenReturn(List.of());

        List<NearbyDeviceDto> list = service.listNearby(31.2304, 121.4737, 10, 10);
        assertEquals(2, list.size());
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
}
