package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商户门户库存 / 临期 / 货道差异 / 补货列表（原 MerchantPortalService inventory 簇）。
 * Pass 3F：从神类拆出，门面仍可委托本类。
 */
@Service
public class MerchantInventoryPortalService {

    private static final String MERCHANT_INVENTORY_VIEW = "merchant:inventory:view";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_PENDING = "PENDING";

    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final InventoryLotService inventoryLotService;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotService deviceSlotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final ReplenishmentTaskLineMapper replenishmentTaskLineRepository;
    private final ReplenishmentRouteMapper replenishmentRouteRepository;
    private final DeviceInfoMapper deviceRepository;

    public MerchantInventoryPortalService(PermissionService permissionService,
                                          MerchantPortalGuard merchantPortalGuard,
                                          MerchantFeaturePackService merchantFeaturePackService,
                                          DeviceSkuInventoryMapper inventoryRepository,
                                          InventoryLotService inventoryLotService,
                                          PullOffTaskMapper pullOffTaskRepository,
                                          DeviceSlotService deviceSlotService,
                                          ReplenishmentTaskMapper replenishmentTaskRepository,
                                          ReplenishmentTaskLineMapper replenishmentTaskLineRepository,
                                          ReplenishmentRouteMapper replenishmentRouteRepository,
                                          DeviceInfoMapper deviceRepository) {
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.inventoryRepository = inventoryRepository;
        this.inventoryLotService = inventoryLotService;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.deviceSlotService = deviceSlotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.replenishmentTaskLineRepository = replenishmentTaskLineRepository;
        this.replenishmentRouteRepository = replenishmentRouteRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceInventoryDto> listInventory(Long userId, String deviceId, boolean lowStockOnly) {
        permissionService.requirePermission(userId, MERCHANT_INVENTORY_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }

        List<DeviceSkuInventory> rows;
        if (deviceId != null && !deviceId.isBlank()) {
            String dev = deviceId.trim();
            if (allowed != null && !allowed.contains(dev)) {
                return List.of();
            }
            rows = inventoryRepository.findByIdDeviceId(dev);
        } else if (lowStockOnly) {
            rows = inventoryRepository.findLowStockLimit(500);
        } else if (allowed != null) {
            rows = inventoryRepository.findByIdDeviceIdIn(allowed);
        } else {
            rows = inventoryRepository.findAllLimit(2000);
        }

        Map<String, Boolean> ledgerByDevice = new HashMap<>();
        Map<String, Map<String, Integer>> sellableByDevice = new HashMap<>();
        return rows.stream()
                .filter(i -> inDeviceScope(allowed, i.getId().getDeviceId()))
                .map(i -> {
                    String dev = i.getId().getDeviceId();
                    String skuId = i.getId().getSkuId();
                    boolean ledger = ledgerByDevice.computeIfAbsent(dev, inventoryLotService::deviceUsesLotLedger);
                    int qty = i.getQuantity();
                    if (ledger) {
                        qty = sellableByDevice.computeIfAbsent(dev, inventoryLotService::sellableQtyBySku)
                                .getOrDefault(skuId, 0);
                    }
                    return new DeviceInventoryDto(
                            dev, skuId, qty, i.getCapacity(), i.getLowThreshold(), i.getUpdatedAt());
                })
                .filter(d -> !lowStockOnly || d.quantity() <= d.lowThreshold())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PullOffTaskDto> listExpiryAlerts(Long userId) {
        permissionService.requirePermission(userId, MERCHANT_INVENTORY_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        return pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN", 500).stream()
                .filter(t -> inDeviceScope(allowed, t.getDeviceId()))
                .map(t -> {
                    int headroom = 0;
                    try {
                        headroom = deviceSlotService.totalHeadroomForSku(t.getDeviceId(), t.getSkuId());
                    } catch (Exception ignored) {
                        headroom = 0;
                    }
                    return new PullOffTaskDto(
                            t.getTaskId(), t.getDeviceId(), t.getSkuId(), t.getLotId(),
                            t.getBatchNo(), t.getQuantity(), t.getReason(), t.getStatus(), t.getCreatedAt(),
                            Math.max(0, headroom));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SlotDiscrepancyAlertDto> listSlotDiscrepancies(Long userId, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_INVENTORY_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        return deviceSlotService.listDiscrepancyAlerts(userId, deviceId).stream()
                .filter(a -> inDeviceScope(allowed, a.deviceId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskDto> listReplenishmentTasks(Long userId, String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantFeaturePackService.requireDevicePack(
                    userId, deviceId.trim(), MerchantFeaturePacks.FIELD);
        }
        List<String> statuses = status != null && !status.isBlank()
                ? List.of(status.trim().toUpperCase())
                : List.of(STATUS_PENDING, STATUS_IN_PROGRESS, "COMPLETED");
        List<ReplenishmentTask> tasks = replenishmentTaskRepository.findByStatusIn(statuses).stream()
                .filter(t -> inDeviceScope(allowed, t.getDeviceId()))
                .filter(t -> deviceId == null || deviceId.isBlank() || deviceId.trim().equals(t.getDeviceId()))
                .sorted(Comparator.comparing(ReplenishmentTask::getCreatedAt).reversed())
                .limit(100)
                .toList();
        Map<Long, ReplenishmentRoute> routesById = loadRoutesById(tasks);
        return tasks.stream()
                .map(t -> toReplenishmentTaskDto(t, routesById.get(t.getRouteId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskLineDto> getReplenishmentTaskLines(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = replenishmentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(
                userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return replenishmentTaskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()
                .map(this::toReplenishmentLineDto)
                .toList();
    }


    private ReplenishmentTaskDto toReplenishmentTaskDto(ReplenishmentTask t) {
        ReplenishmentRoute route = t.getRouteId() == null
                ? null
                : replenishmentRouteRepository.findById(t.getRouteId()).orElse(null);
        return toReplenishmentTaskDto(t, route);
    }

    private ReplenishmentTaskDto toReplenishmentTaskDto(ReplenishmentTask t, ReplenishmentRoute route) {
        String deviceName = null;
        if (t.getDeviceId() != null) {
            deviceName = deviceRepository.findById(t.getDeviceId())
                    .map(DeviceInfo::getDeviceName)
                    .orElse(null);
        }
        return new ReplenishmentTaskDto(
                t.getTaskId(), t.getRouteId(), t.getDeviceId(), t.getAssigneeUserId(),
                t.getStatus(), t.getNotes(), t.getCompletedAt(),
                t.getCheckInAt(), t.getCheckInLat(), t.getCheckInLng(),
                resolveCheckInDistanceM(t),
                t.getRequestId(), t.getOutboundId(), t.getCreatedAt(),
                deviceName,
                route != null ? route.getRouteName() : null,
                route != null ? route.getPlannedDate() : null
        );
    }

    private Map<Long, ReplenishmentRoute> loadRoutesById(Collection<ReplenishmentTask> tasks) {
        Set<Long> routeIds = tasks.stream()
                .map(ReplenishmentTask::getRouteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (routeIds.isEmpty()) {
            return Map.of();
        }
        return replenishmentRouteRepository.findAllById(routeIds).stream()
                .collect(Collectors.toMap(ReplenishmentRoute::getRouteId, r -> r, (a, b) -> a));
    }

    private Double resolveCheckInDistanceM(ReplenishmentTask t) {
        if (t.getCheckInLat() == null || t.getCheckInLng() == null || t.getDeviceId() == null) {
            return null;
        }
        DeviceInfo device = deviceRepository.findById(t.getDeviceId()).orElse(null);
        return haversineCheckInDistanceM(device, t.getCheckInLat(), t.getCheckInLng());
    }

    /** 签到点与柜机坐标距离（米）；任一坐标缺失返回 null。 */
    static Double haversineCheckInDistanceM(DeviceInfo device, Double checkInLat, Double checkInLng) {
        if (device == null || checkInLat == null || checkInLng == null
                || device.getLatitude() == null || device.getLongitude() == null) {
            return null;
        }
        double r = 6371000;
        double dLat = Math.toRadians(checkInLat - device.getLatitude());
        double dLon = Math.toRadians(checkInLng - device.getLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(device.getLatitude())) * Math.cos(Math.toRadians(checkInLat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private ReplenishmentTaskLineDto toReplenishmentLineDto(ReplenishmentTaskLine line) {
        return new ReplenishmentTaskLineDto(
                line.getLineId(), line.getLineType(), line.getSkuId(), line.getBatchNo(),
                line.getProductionDate(), line.getExpiryDate(), line.getQuantity(),
                line.getSlotId(), line.isApplied()
        );
    }

    private static boolean inDeviceScope(Set<String> allowed, String deviceId) {
        if (deviceId == null) return false;
        return allowed == null || allowed.contains(deviceId);
    }

}
