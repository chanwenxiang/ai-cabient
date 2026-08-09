package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PlanRouteRequest;
import com.aicabinet.trade.config.RoutePlanningProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlanningServiceTest {

    @Mock private DeviceInfoMapper deviceRepository;

    private RoutePlanningService service(String provider, String gaodeKey) {
        return new RoutePlanningService(deviceRepository,
                new RoutePlanningProperties(provider, gaodeKey), RestClient.builder());
    }

    @Test
    void planNearest_shouldOrderByDistanceAndSumLegs() {
        DeviceInfo near = device("NEAR", 31.2300, 121.4730);
        DeviceInfo far = device("FAR", 31.2400, 121.4900);

        RoutePlanningService.PlannedRoute route =
                service("NEAREST", "").planNearest(List.of(far, near), 31.2304, 121.4737);

        assertEquals(2, route.waypoints().size());
        assertEquals("NEAR", route.waypoints().get(0).deviceId());
        assertEquals("FAR", route.waypoints().get(1).deviceId());
        assertEquals(1, route.waypoints().get(0).sequence());
        assertEquals(2, route.waypoints().get(1).sequence());
        assertTrue(route.totalDistanceM() > 0);
        assertTrue(route.totalDistanceM() >= route.waypoints().get(0).distanceFromPrevM()
                + route.waypoints().get(1).distanceFromPrevM() - 1);
    }

    @Test
    void parseGaodeDistances_shouldReturnMetersInResultsOrder() {
        String json = """
                {"status":"1","info":"OK","results":[
                  {"origin_id":"0","dest_id":"0","distance":"1234","duration":"60"},
                  {"origin_id":"1","dest_id":"0","distance":"2345","duration":"90"}
                ]}
                """;

        assertEquals(List.of(1234, 2345), RoutePlanningService.parseGaodeDistances(json));
    }

    @Test
    void parseGaodeDistances_shouldThrowWhenStatusNotOk() {
        String json = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"results\":[]}";

        assertThrows(IllegalStateException.class,
                () -> RoutePlanningService.parseGaodeDistances(json));
    }

    @Test
    void parseGaodeDistances_shouldThrowOnEmptyResult() {
        String json = "{\"status\":\"1\",\"info\":\"OK\",\"results\":[]}";

        assertThrows(IllegalStateException.class,
                () -> RoutePlanningService.parseGaodeDistances(json));
    }

    @Test
    void plan_shouldFallbackToNearestWhenGaodeNotConfigured() {
        when(deviceRepository.findById("A")).thenReturn(Optional.of(device("A", 31.2304, 121.4737)));
        when(deviceRepository.findById("B")).thenReturn(Optional.of(device("B", 31.2300, 121.4730)));

        RoutePlanningService.PlannedRoute route = service("NEAREST", "")
                .plan(new PlanRouteRequest("R1", null, LocalDate.now(), List.of("A", "B"), null, null));

        assertEquals(2, route.waypoints().size());
        assertEquals("A", route.waypoints().get(0).deviceId());
        assertEquals("B", route.waypoints().get(1).deviceId());
    }

    @Test
    void plan_shouldFallbackToNearestWhenGaodeKeyBlank() {
        when(deviceRepository.findById("A")).thenReturn(Optional.of(device("A", 31.2304, 121.4737)));
        when(deviceRepository.findById("B")).thenReturn(Optional.of(device("B", 31.2300, 121.4730)));

        RoutePlanningService.PlannedRoute route = service("GAODE", "")
                .plan(new PlanRouteRequest("R1", null, LocalDate.now(), List.of("A", "B"), null, null));

        assertEquals(2, route.waypoints().size());
    }

    private static DeviceInfo device(String deviceId, double lat, double lng) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(deviceId);
        d.setDeviceName(deviceId);
        d.setLatitude(lat);
        d.setLongitude(lng);
        d.setAddress(deviceId + "地址");
        return d;
    }
}
