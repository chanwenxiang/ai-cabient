package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PlanRouteRequest;
import com.aicabinet.common.dto.RouteWaypointDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 补货路线规划：最近邻启发式（生产可替换为高德/百度路径规划 API）。
 */
@Service
public class RoutePlanningService {

    private static final double DEFAULT_START_LAT = 31.2304;
    private static final double DEFAULT_START_LNG = 121.4737;

    private final DeviceInfoMapper deviceRepository;

    public RoutePlanningService(DeviceInfoMapper deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public record PlannedRoute(List<RouteWaypointDto> waypoints, int totalDistanceM) {}

    public PlannedRoute plan(PlanRouteRequest request) {
        if (request.deviceIds() == null || request.deviceIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_IDS_REQUIRED);
        }

        double curLat = request.startLatitude() != null ? request.startLatitude() : DEFAULT_START_LAT;
        double curLng = request.startLongitude() != null ? request.startLongitude() : DEFAULT_START_LNG;

        Set<String> remaining = new HashSet<>(request.deviceIds());
        List<RouteWaypointDto> waypoints = new ArrayList<>();
        int totalDistance = 0;
        int seq = 1;

        while (!remaining.isEmpty()) {
            String nearest = null;
            DeviceInfo nearestDevice = null;
            double nearestDist = Double.MAX_VALUE;

            for (String deviceId : remaining) {
                DeviceInfo device = deviceRepository.findById(deviceId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                ApiMessages.DEVICE_NOT_FOUND + "：" + deviceId));
                double lat = device.getLatitude() != null ? device.getLatitude() : curLat;
                double lng = device.getLongitude() != null ? device.getLongitude() : curLng;
                double dist = haversineMeters(curLat, curLng, lat, lng);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = deviceId;
                    nearestDevice = device;
                }
            }

            remaining.remove(nearest);
            int legM = (int) Math.round(nearestDist);
            totalDistance += legM;
            double destLat = nearestDevice.getLatitude() != null ? nearestDevice.getLatitude() : curLat;
            double destLng = nearestDevice.getLongitude() != null ? nearestDevice.getLongitude() : curLng;
            waypoints.add(new RouteWaypointDto(
                    seq++, nearest, destLat, destLng,
                    nearestDevice.getAddress() != null ? nearestDevice.getAddress() : nearestDevice.getDeviceName(),
                    legM
            ));
            curLat = destLat;
            curLng = destLng;
        }

        waypoints.sort(Comparator.comparingInt(RouteWaypointDto::sequence));
        return new PlannedRoute(waypoints, totalDistance);
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
