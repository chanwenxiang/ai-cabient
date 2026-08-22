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
import static org.mockito.ArgumentMatchers.anyString;
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
        when(deviceRepository.findAllOrderByDeviceIdAsc()).thenReturn(List.of(far, near));
        when(deviceValidationService.getDeviceStatus(anyString())).thenAnswer(inv ->
                new com.aicabinet.common.dto.DeviceStatusDto(
                        inv.getArgument(0), "n", "ONLINE", true, true, null, null, "NONE", 0));
        when(deviceCatalogService.listProducts(anyString())).thenReturn(List.of());

        List<NearbyDeviceDto> list = service.listNearby(31.2304, 121.4737, 10, 10);
        assertEquals(2, list.size());
        assertEquals("CAB-NEAR", list.get(0).deviceId());
        assertTrue(list.get(0).distanceMeters() < list.get(1).distanceMeters());
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
