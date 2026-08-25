package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.NearbyDeviceDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class NearbyDeviceService {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private final DeviceInfoMapper deviceRepository;
    private final DeviceCatalogService deviceCatalogService;
    private final DeviceValidationService deviceValidationService;

    public NearbyDeviceService(DeviceInfoMapper deviceRepository,
                               DeviceCatalogService deviceCatalogService,
                               DeviceValidationService deviceValidationService) {
        this.deviceRepository = deviceRepository;
        this.deviceCatalogService = deviceCatalogService;
        this.deviceValidationService = deviceValidationService;
    }

    @Transactional(readOnly = true)
    public List<NearbyDeviceDto> listNearby(double latitude, double longitude, double radiusKm, int limit) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位坐标无效");
        }
        double radius = radiusKm <= 0 ? 5.0 : Math.min(radiusKm, 50.0);
        int max = limit <= 0 ? 20 : Math.min(limit, 50);
        double radiusM = radius * 1000.0;

        List<NearbyDeviceDto> out = new ArrayList<>();
        for (DeviceInfo d : deviceRepository.findAllOrderByDeviceIdAsc()) {
            if (d.getLatitude() == null || d.getLongitude() == null) {
                continue;
            }
            String life = d.getLifecycleStatus() == null ? "" : d.getLifecycleStatus().trim().toUpperCase();
            if (life.equals("RETIRED") || life.equals("WAREHOUSE") || life.equals("SCRAPPED")
                    || life.equals("DECOMMISSIONED")) {
                continue;
            }
            double dist = haversineMeters(latitude, longitude, d.getLatitude(), d.getLongitude());
            if (dist > radiusM) {
                continue;
            }
            out.add(toDto(d, dist));
        }
        out.sort(Comparator.comparingDouble(NearbyDeviceDto::distanceMeters));
        if (out.size() > max) {
            return out.subList(0, max);
        }
        return out;
    }

    private NearbyDeviceDto toDto(DeviceInfo d, double distanceMeters) {
        boolean available = false;
        try {
            available = deviceValidationService.getDeviceStatus(d.getDeviceId()).available();
        } catch (Exception ignored) {
            available = !d.salesLockedEnabled();
        }
        List<DeviceProductDto> products = List.of();
        try {
            products = deviceCatalogService.listProducts(d.getDeviceId());
        } catch (Exception ignored) {
            // preview optional
        }
        int itemCount = products.stream().mapToInt(p -> Math.max(0, p.quantity())).sum();
        List<NearbyDeviceDto.PreviewSku> preview = products.stream()
                .sorted((a, b) -> Integer.compare(b.quantity(), a.quantity()))
                .limit(3)
                .map(p -> new NearbyDeviceDto.PreviewSku(
                        p.skuId(), p.skuName(), p.quantity(), p.priceCents()))
                .toList();
        return new NearbyDeviceDto(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getAddress(),
                d.getLatitude(),
                d.getLongitude(),
                Math.round(distanceMeters * 10.0) / 10.0,
                d.getOnlineStatus(),
                available,
                products.size(),
                itemCount,
                preview
        );
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
