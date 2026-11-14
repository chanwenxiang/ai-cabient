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
import java.util.Map;

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
        validateCoordinates(latitude, longitude);
        double radiusM = resolveRadiusMeters(radiusKm);
        int max = resolveResultLimit(limit);

        // R20 P2-16：粗筛下推（SQL 外接矩形 + 有坐标 + 生命周期投放态）→ 精筛（haversine 半径）。
        // 原实现 findAllOrderByDeviceIdAsc 全表载回内存再逐个算距离。
        double latSpan = Math.toDegrees(radiusM / EARTH_RADIUS_M);
        double minLat = Math.max(-90d, latitude - latSpan);
        double maxLat = Math.min(90d, latitude + latSpan);
        Double minLon = null;
        Double maxLon = null;
        double cosLat = Math.cos(Math.toRadians(latitude));
        if (Math.abs(cosLat) > 1e-9) {
            // 经度 1 度的实际米数随纬度收缩；除 cos 得到该纬度上的经度跨度。
            double lonSpan = Math.toDegrees(radiusM / (EARTH_RADIUS_M * Math.abs(cosLat)));
            double lo = longitude - lonSpan;
            double hi = longitude + lonSpan;
            // 跨 ±180（日界线）或跨度大到盖住半球时，BETWEEN 无法表达环绕 ⇒ 不按经度过滤，
            // 只留纬度谓词（仍是圆的超集，不会漏柜机，见 DeviceInfoMapper 注释）。
            if (lonSpan < 180d && lo >= -180d && hi <= 180d) {
                minLon = lo;
                maxLon = hi;
            }
        }

        List<DeviceInfo> candidates = deviceRepository.findNearbyCandidates(minLat, maxLat, minLon, maxLon);
        // 2026-11-14：可用性改成一次批量问（3 条查询，与台数无关），替掉逐台 getDeviceStatus 的 50×3 查询。
        // 语义与 getDeviceStatus(...).available() 逐字相同；等价性由 NearbyAvailabilityBatchSqlIT 守着。
        Map<String, Boolean> availableByDevice;
        try {
            availableByDevice = deviceValidationService.availableByDevices(
                    candidates.stream().map(DeviceInfo::getDeviceId).toList());
        } catch (Exception e) {
            // 批量查询整体失败时退回与单台版 catch 分支同一条判据（在线且未锁），仍是宁少不多
            availableByDevice = Map.of();
        }
        // 预览也批量：一次取多台的可售量 + 只给前 3 个算价（原路径每台 6+4n 条查询）。
        // 语义与「逐台 listProducts 再取前 3」逐字一致，等价性由 NearbyPreviewBatchSqlIT 守着。
        Map<String, DeviceCatalogService.DevicePreview> previews;
        try {
            previews = deviceCatalogService.previewByDevices(
                    candidates.stream().map(DeviceInfo::getDeviceId).toList(), null, 3);
        } catch (Exception e) {
            previews = Map.of();
        }
        List<NearbyDeviceDto> out = new ArrayList<>();
        for (DeviceInfo d : candidates) {
            // 坐标非空与生命周期已由 SQL 保证（同判据、同归一化口径），这里只剩距离判据。
            double dist = haversineMeters(latitude, longitude, d.getLatitude(), d.getLongitude());
            if (dist <= radiusM) {
                out.add(toDto(d, dist, availableByDevice.get(d.getDeviceId()), previews.get(d.getDeviceId())));
            }
        }
        out.sort(Comparator.comparingDouble(NearbyDeviceDto::distanceMeters));
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位坐标无效");
        }
    }

    private static double resolveRadiusMeters(double radiusKm) {
        double radius = radiusKm <= 0 ? 5.0 : Math.min(radiusKm, 50.0);
        return radius * 1000.0;
    }

    private static int resolveResultLimit(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, 50);
    }

    private NearbyDeviceDto toDto(DeviceInfo d, double distanceMeters, Boolean batchAvailable,
                                   DeviceCatalogService.DevicePreview batchPreview) {
        boolean available;
        if (batchAvailable != null) {
            available = batchAvailable;
        } else {
            // 批量结果里没有这台（或批量整体失败）⇒ 退回与单台版 catch 同一判据：必须在线且未锁。
            String life = d.getOnlineStatus() == null ? "" : d.getOnlineStatus().trim();
            available = "ONLINE".equalsIgnoreCase(life) && !d.salesLockedEnabled();
        }
        int itemCount;
        int skuCount;
        List<NearbyDeviceDto.PreviewSku> preview;
        if (batchPreview != null) {
            itemCount = batchPreview.itemCount();
            skuCount = batchPreview.skuCount();
            preview = batchPreview.preview();
        } else {
            // 批量结果里没有这台（或批量整体失败）⇒ 退回逐台口径，结果与改造前完全一致
            List<DeviceProductDto> products = List.of();
            try {
                products = deviceCatalogService.listProducts(d.getDeviceId());
            } catch (Exception ignored) {
                // preview optional
            }
            itemCount = products.stream().mapToInt(p -> Math.max(0, p.quantity())).sum();
            skuCount = products.size();
            preview = products.stream()
                    .sorted((a, b) -> Integer.compare(b.quantity(), a.quantity()))
                    .limit(3)
                    .map(p -> new NearbyDeviceDto.PreviewSku(
                            p.skuId(), p.skuName(), p.quantity(), p.priceCents()))
                    .toList();
        }
        return new NearbyDeviceDto(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getAddress(),
                d.getLatitude(),
                d.getLongitude(),
                Math.round(distanceMeters * 10.0) / 10.0,
                d.getOnlineStatus(),
                available,
                skuCount,
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
