package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PlanRouteRequest;
import com.aicabinet.common.dto.RouteWaypointDto;
import com.aicabinet.trade.config.RoutePlanningProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补货路线规划。
 *
 * <p>默认用最近邻启发式 + Haversine 直线距离；配置 {@code provider=GAODE} 且提供高德
 * Key 后，改用高德驾车距离矩阵的真实路网距离做最近邻选点，API 异常时自动回退直线距离，
 * 保证补货任务创建不中断。</p>
 */
@Service
public class RoutePlanningService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanningService.class);

    private final DeviceInfoMapper deviceRepository;
    private final RoutePlanningProperties routeProperties;
    private final RestClient restClient;

    public RoutePlanningService(DeviceInfoMapper deviceRepository,
                                RoutePlanningProperties routeProperties,
                                RestClient.Builder restClientBuilder) {
        this.deviceRepository = deviceRepository;
        this.routeProperties = routeProperties;
        this.restClient = restClientBuilder.build();
    }

    public record PlannedRoute(List<RouteWaypointDto> waypoints, int totalDistanceM) {
    }

    public PlannedRoute plan(PlanRouteRequest request) {
        if (request.deviceIds() == null || request.deviceIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_IDS_REQUIRED);
        }
        List<DeviceInfo> devices = resolveDevices(request.deviceIds());
        double[] start = resolveStart(request, devices);

        if (routeProperties.gaodeEnabled()) {
            try {
                return planWithGaode(devices, start[0], start[1]);
            } catch (Exception e) {
                log.warn("gaode route planning failed, fallback to nearest-neighbor: {}", e.getMessage());
            }
        }
        return planNearest(devices, start[0], start[1]);
    }

    /**
     * 起点：请求坐标优先；缺省时用首台有坐标的柜机（首段距离为 0），
     * 避免静默落回固定城市导致跨城巨距（OBS-021）。
     */
    private static double[] resolveStart(PlanRouteRequest request, List<DeviceInfo> devices) {
        if (request.startLatitude() != null && request.startLongitude() != null) {
            return new double[]{request.startLatitude(), request.startLongitude()};
        }
        for (DeviceInfo device : devices) {
            if (device.getLatitude() != null && device.getLongitude() != null) {
                return new double[]{device.getLatitude(), device.getLongitude()};
            }
        }
        return new double[]{0d, 0d};
    }

    /** 最近邻 + Haversine 直线距离（兜底方案）。 */
    PlannedRoute planNearest(List<DeviceInfo> devices, double startLat, double startLng) {
        List<DeviceInfo> remaining = new ArrayList<>(devices);
        List<RouteWaypointDto> waypoints = new ArrayList<>();
        double curLat = startLat;
        double curLng = startLng;
        int totalDistance = 0;
        int seq = 1;

        while (!remaining.isEmpty()) {
            DeviceInfo nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (DeviceInfo device : remaining) {
                double lat = device.getLatitude() != null ? device.getLatitude() : curLat;
                double lng = device.getLongitude() != null ? device.getLongitude() : curLng;
                double dist = haversineMeters(curLat, curLng, lat, lng);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = device;
                }
            }
            remaining.remove(nearest);
            int legM = (int) Math.round(nearestDist);
            totalDistance += legM;
            double destLat = nearest.getLatitude() != null ? nearest.getLatitude() : curLat;
            double destLng = nearest.getLongitude() != null ? nearest.getLongitude() : curLng;
            waypoints.add(new RouteWaypointDto(
                    seq++, nearest.getDeviceId(), destLat, destLng,
                    address(nearest), legM
            ));
            curLat = destLat;
            curLng = destLng;
        }
        return new PlannedRoute(waypoints, totalDistance);
    }

    /** 高德驾车距离矩阵 + 最近邻；任一步失败抛出异常由调用方回退。 */
    private PlannedRoute planWithGaode(List<DeviceInfo> devices, double startLat, double startLng) {
        List<DeviceInfo> remaining = new ArrayList<>(devices);
        List<RouteWaypointDto> waypoints = new ArrayList<>();
        double curLat = startLat;
        double curLng = startLng;
        int totalDistance = 0;
        int seq = 1;

        while (!remaining.isEmpty()) {
            Map<DeviceInfo, Integer> roadMeters = new LinkedHashMap<>();
            List<DeviceInfo> routable = new ArrayList<>();
            for (DeviceInfo device : remaining) {
                if (device.getLatitude() == null || device.getLongitude() == null) {
                    roadMeters.put(device, null); // 缺坐标的点用直线距离兜底
                } else {
                    routable.add(device);
                }
            }
            if (!routable.isEmpty()) {
                String origins = routable.stream()
                        .map(d -> d.getLongitude() + "," + d.getLatitude())
                        .reduce((a, b) -> a + ";" + b)
                        .orElse("");
                List<Integer> meters = gaodeDistances(origins, curLng + "," + curLat);
                for (int i = 0; i < routable.size(); i++) {
                    roadMeters.put(routable.get(i), meters.get(i));
                }
            }

            DeviceInfo nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (DeviceInfo device : remaining) {
                double dist;
                Integer road = roadMeters.get(device);
                if (road != null) {
                    dist = road;
                } else {
                    double lat = device.getLatitude() != null ? device.getLatitude() : curLat;
                    double lng = device.getLongitude() != null ? device.getLongitude() : curLng;
                    dist = haversineMeters(curLat, curLng, lat, lng);
                }
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = device;
                }
            }

            remaining.remove(nearest);
            int legM = (int) Math.round(nearestDist);
            totalDistance += legM;
            double destLat = nearest.getLatitude() != null ? nearest.getLatitude() : curLat;
            double destLng = nearest.getLongitude() != null ? nearest.getLongitude() : curLng;
            waypoints.add(new RouteWaypointDto(
                    seq++, nearest.getDeviceId(), destLat, destLng,
                    address(nearest), legM
            ));
            curLat = destLat;
            curLng = destLng;
        }
        return new PlannedRoute(waypoints, totalDistance);
    }

    /** 调用高德距离矩阵：origins 多个、destination 一个，按 origins 顺序返回距离（米）。 */
    private List<Integer> gaodeDistances(String origins, String destination) {
        String body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("restapi.amap.com")
                        .path("/v3/distance")
                        .queryParam("origins", origins)
                        .queryParam("destination", destination)
                        .queryParam("type", "1")
                        .queryParam("extensions", "base")
                        .queryParam("key", routeProperties.gaodeKey())
                        .build())
                .retrieve()
                .body(String.class);
        return parseGaodeDistances(body);
    }

    /** 解析高德 /v3/distance 响应，按 results 顺序返回距离（米）；异常时抛 IllegalStateException。 */
    static List<Integer> parseGaodeDistances(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("gaode distance empty response");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("gaode distance invalid json: " + e.getMessage(), e);
        }
        if (!"1".equals(root.path("status").asText())) {
            throw new IllegalStateException("gaode distance status="
                    + root.path("status").asText() + " info=" + root.path("info").asText());
        }
        List<Integer> out = new ArrayList<>();
        var results = root.path("results");
        for (var node : results) {
            out.add(Integer.parseInt(node.path("distance").asText()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("gaode distance empty results");
        }
        return out;
    }

    private List<DeviceInfo> resolveDevices(List<String> deviceIds) {
        List<DeviceInfo> devices = new ArrayList<>();
        for (String deviceId : deviceIds) {
            devices.add(deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ApiMessages.DEVICE_NOT_FOUND + "：" + deviceId)));
        }
        return devices;
    }

    private static String address(DeviceInfo device) {
        return device.getAddress() != null ? device.getAddress() : device.getDeviceName();
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
