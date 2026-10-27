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
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.DeviceLocationSupport;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.support.SkuDisplayNames;
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
    private final FileAttachmentService fileAttachmentService;
    private final SkuCatalogMapper skuCatalogMapper;
    private final com.aicabinet.trade.mapper.WarehouseOutboundMapper outboundRepository;
    private final com.aicabinet.trade.mapper.WarehouseOutboundLineMapper outboundLineRepository;
    private final com.aicabinet.trade.mapper.WarehouseInventoryMapper warehouseInventoryRepository;

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
                                          DeviceInfoMapper deviceRepository,
                                          FileAttachmentService fileAttachmentService,
                                          SkuCatalogMapper skuCatalogMapper,
                                          com.aicabinet.trade.mapper.WarehouseOutboundMapper outboundRepository,
                                          com.aicabinet.trade.mapper.WarehouseOutboundLineMapper outboundLineRepository,
                                          com.aicabinet.trade.mapper.WarehouseInventoryMapper warehouseInventoryRepository) {
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
        this.fileAttachmentService = fileAttachmentService;
        this.skuCatalogMapper = skuCatalogMapper;
        this.outboundRepository = outboundRepository;
        this.outboundLineRepository = outboundLineRepository;
        this.warehouseInventoryRepository = warehouseInventoryRepository;
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

        Map<String, Set<String>> ledgerSkusByDevice = new HashMap<>();
        Map<String, Map<String, Integer>> sellableByDevice = new HashMap<>();
        return rows.stream()
                .filter(i -> inDeviceScope(allowed, i.getId().getDeviceId()))
                .map(i -> {
                    String dev = i.getId().getDeviceId();
                    String skuId = i.getId().getSkuId();
                    // 🔴 已建账按 SKU 粒度判断（与 DeviceSlotService.toInventoryDto 同口径）：
                    // 设备级 deviceUsesLotLedger 会把「从未进过账本」的 SKU 读成 0——
                    // 账面有货却显示缺货（2026-10-27 雪碧实测：inventory=2、账本零行）。
                    Set<String> lotSkus = ledgerSkusByDevice.computeIfAbsent(
                            dev, inventoryLotService::lotLedgerSkuIds);
                    int qty = i.getQuantity();
                    if (lotSkus.contains(skuId)) {
                        qty = sellableByDevice.computeIfAbsent(dev, inventoryLotService::sellableQtyBySku)
                                .getOrDefault(skuId, 0);
                    }
                    return new DeviceInventoryDto(
                            dev, skuId, qty, i.getCapacity(), i.getLowThreshold(), i.getUpdatedAt(),
                            i.getVersion(), i.getLastRestockQty());
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

    /**
     * 商户「缺货巡柜」聚合（P2 口径统一，2026-10-15）：货道级 isSlotShortage 单源判定，
     * 缺口件数与补货建议 headroom 同口径（补到 par、受 maxLevel 限幅）。按缺口货道数降序取前 5。
     */
    @Transactional(readOnly = true)
    public List<MerchantShortageSummaryDto> listShortageSummary(Long userId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        List<DeviceInfo> devices = deviceRepository.findAll().stream()
                .filter(d -> inDeviceScope(allowed, d.getDeviceId()))
                .toList();
        return devices.stream()
                .map(dev -> {
                    List<DeviceSlotService.SlotShortageItem> slots =
                            deviceSlotService.listShortageSlots(dev.getDeviceId());
                    int shortageQty = slots.stream()
                            .mapToInt(s -> {
                                int target = s.parLevel() > 0 ? s.parLevel() : s.capacity();
                                int cap = s.capacity() > 0 ? s.capacity() : target;
                                return Math.max(0, Math.min(target, cap) - s.bookQty());
                            })
                            .sum();
                    return new MerchantShortageSummaryDto(
                            dev.getDeviceId(), dev.getDeviceName(), slots.size(), shortageQty);
                })
                .filter(r -> r.shortageSlots() > 0)
                .sorted(Comparator.comparingInt(MerchantShortageSummaryDto::shortageSlots).reversed()
                        .thenComparing(Comparator.comparingInt(MerchantShortageSummaryDto::shortageQty).reversed()))
                .limit(5)
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
        Set<Long> taskIds = tasks.stream()
                .map(ReplenishmentTask::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Integer> evidenceByTask = fileAttachmentService.countReplenishmentEvidenceByTaskIds(taskIds);
        Map<Long, String> lineSummaryByTask = loadLineSummariesByTaskIds(taskIds);
        return tasks.stream()
                .map(t -> toReplenishmentTaskDto(
                        t,
                        routesById.get(t.getRouteId()),
                        evidenceByTask.getOrDefault(t.getTaskId(), 0),
                        lineSummaryByTask.getOrDefault(t.getTaskId(), "")))
                .toList();
    }

    /**
     * 首页「备货清单」（CB-072）：本人范围内进行中补货任务的出库行按 SKU 聚合，
     * pack_qty 换算「X 件 + Y 支」，带相关仓库库存提示。数量口径=支（CB-061）。
     */
    @Transactional(readOnly = true)
    public List<MerchantPrepListDto> replenishmentPrepList(Long userId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        List<ReplenishmentTask> tasks = replenishmentTaskRepository
                .findByStatusIn(List.of("IN_PROGRESS")).stream()
                .filter(t -> inDeviceScope(allowed, t.getDeviceId()))
                .filter(t -> t.getOutboundId() != null)
                .toList();
        Map<String, Integer> unitsBySku = new java.util.LinkedHashMap<>();
        Map<String, java.util.LinkedHashMap<Long, Integer>> taskQtyBySku = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> warehousesBySku = new java.util.LinkedHashMap<>();
        Map<Long, String> deviceByTask = new java.util.HashMap<>();
        for (ReplenishmentTask task : tasks) {
            com.aicabinet.trade.domain.WarehouseOutbound outbound =
                    outboundRepository.selectById(task.getOutboundId());
            if (outbound == null) {
                continue;
            }
            deviceByTask.put(task.getTaskId(), task.getDeviceId());
            for (com.aicabinet.trade.domain.WarehouseOutboundLine line : outboundLineRepository
                    .findByOutboundIdAndDeviceIdOrderByLineIdAsc(task.getOutboundId(), task.getDeviceId())) {
                // 备货只排除作废行（CB-072 站点自仓口径）：天天补货从分仓（站点自仓）出货，
                // 本部仓只对采购——交接状态（PENDING/IN_TRANSIT/…）在自仓直配流程不作为排除依据
                if ("CANCELLED".equals(line.getHandoverStatus())) {
                    continue;
                }
                unitsBySku.merge(line.getSkuId(), line.getQuantity(), Integer::sum);
                taskQtyBySku.computeIfAbsent(line.getSkuId(), k -> new java.util.LinkedHashMap<>())
                        .merge(task.getTaskId(), line.getQuantity(), Integer::sum);
                warehousesBySku.computeIfAbsent(line.getSkuId(), k -> new java.util.HashSet<>())
                        .add(outbound.getWarehouseId());
            }
        }
        if (unitsBySku.isEmpty()) {
            return List.of();
        }
        Set<String> skuIds = unitsBySku.keySet();
        Map<String, com.aicabinet.trade.domain.SkuCatalog> catalogBySku =
                skuCatalogMapper.selectList(
                                com.baomidou.mybatisplus.core.toolkit.Wrappers
                                        .<com.aicabinet.trade.domain.SkuCatalog>lambdaQuery()
                                        .in(com.aicabinet.trade.domain.SkuCatalog::getSkuId, skuIds))
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.aicabinet.trade.domain.SkuCatalog::getSkuId,
                                s -> s, (a, b) -> a));
        List<MerchantPrepListDto> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : unitsBySku.entrySet()) {
            String skuId = e.getKey();
            int totalUnits = e.getValue();
            com.aicabinet.trade.domain.SkuCatalog catalog = catalogBySku.get(skuId);
            int packQty = Math.max(1, catalog == null ? 1 : catalog.getPackQty());
            int stockUnits = warehousesBySku.getOrDefault(skuId, java.util.Set.of()).stream()
                    .mapToInt(wid -> warehouseInventoryRepository
                            .selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                    .<com.aicabinet.trade.domain.WarehouseInventory>lambdaQuery()
                                    .eq(com.aicabinet.trade.domain.WarehouseInventory::getWarehouseId, wid)
                                    .eq(com.aicabinet.trade.domain.WarehouseInventory::getSkuId, skuId))
                            .stream()
                            .mapToInt(com.aicabinet.trade.domain.WarehouseInventory::getQuantity)
                            .sum())
                    .sum();
            List<MerchantPrepListDto.TaskRef> taskRefs = taskQtyBySku
                    .getOrDefault(skuId, new java.util.LinkedHashMap<>())
                    .entrySet().stream()
                    .map(t -> new MerchantPrepListDto.TaskRef(
                            t.getKey(), deviceByTask.get(t.getKey()), t.getValue()))
                    .toList();
            out.add(new MerchantPrepListDto(
                    skuId,
                    catalog == null ? skuId : catalog.getSkuName(),
                    catalog == null ? "支" : catalog.getUnit(),
                    packQty,
                    totalUnits,
                    totalUnits / packQty,
                    totalUnits % packQty,
                    stockUnits,
                    stockUnits / packQty,
                    stockUnits < totalUnits,
                    taskRefs.size(),
                    taskRefs));
        }
        out.sort(java.util.Comparator.comparingInt(MerchantPrepListDto::totalUnits).reversed());
        return out;
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
        return toReplenishmentTaskDto(t, route, null, null);
    }

    private ReplenishmentTaskDto toReplenishmentTaskDto(ReplenishmentTask t, ReplenishmentRoute route) {
        return toReplenishmentTaskDto(t, route, null, null);
    }

    private ReplenishmentTaskDto toReplenishmentTaskDto(
            ReplenishmentTask t, ReplenishmentRoute route, Integer evidenceCount, String lineSummary) {
        String deviceName = null;
        Boolean deviceHasCoords = null;
        if (t.getDeviceId() != null) {
            DeviceInfo device = deviceRepository.findById(t.getDeviceId()).orElse(null);
            if (device != null) {
                deviceName = device.getDeviceName();
                deviceHasCoords = DeviceLocationSupport.hasCoords(device);
            }
        }
        return new ReplenishmentTaskDto(
                t.getTaskId(), t.getRouteId(), t.getDeviceId(), t.getAssigneeUserId(),
                t.getStatus(), t.getNotes(), t.getCompletedAt(),
                t.getCheckInAt(), t.getCheckInLat(), t.getCheckInLng(),
                resolveCheckInDistanceM(t),
                t.getOutboundId(), t.getCreatedAt(),
                deviceName,
                route != null ? route.getRouteName() : null,
                route != null ? route.getPlannedDate() : null,
                evidenceCount,
                lineSummary,
                deviceHasCoords
        );
    }

    private Map<Long, String> loadLineSummariesByTaskIds(Collection<Long> taskIds) {
        Map<Long, String> out = new HashMap<>();
        if (taskIds == null || taskIds.isEmpty()) {
            return out;
        }
        Map<Long, List<ReplenishmentTaskLine>> byTask = replenishmentTaskLineRepository
                .findByTaskIdIn(taskIds)
                .stream()
                .collect(Collectors.groupingBy(ReplenishmentTaskLine::getTaskId));
        for (Long taskId : taskIds) {
            out.put(taskId, formatLineSummary(byTask.getOrDefault(taskId, List.of())));
        }
        return out;
    }

    private static String formatLineSummary(List<ReplenishmentTaskLine> rows) {
        if (rows == null || rows.isEmpty()) {
            return "暂无明细行";
        }
        int qty = rows.stream().mapToInt(l -> Math.max(0, l.getQuantity())).sum();
        long pull = rows.stream().filter(l -> isPullOffType(l.getLineType())).count();
        long restock = rows.size() - pull;
        long noExpiry = rows.stream()
                .filter(l -> l.getExpiryDate() == null)
                .count();
        long noSlot = rows.stream()
                .filter(l -> (l.getSlotId() == null || l.getSlotId().isBlank()) && !isPullOffType(l.getLineType()))
                .count();
        StringBuilder parts = new StringBuilder();
        parts.append(rows.size()).append(" 行 · 共 ").append(qty).append(" 件");
        if (restock > 0) {
            parts.append(" · 补货 ").append(restock);
        }
        if (pull > 0) {
            parts.append(" · 下架 ").append(pull);
        }
        if (noSlot > 0) {
            parts.append(" · ").append(noSlot).append(" 行待选货道");
        }
        if (noExpiry > 0) {
            parts.append(" · ").append(noExpiry).append(" 行缺效期");
        }
        return parts.toString();
    }

    private static boolean isPullOffType(String lineType) {
        String code = (lineType == null || lineType.isBlank() ? "RESTOCK" : lineType).trim().toUpperCase();
        return "PULL_OFF".equals(code) || "REMOVE".equals(code) || "PULL".equals(code);
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
        var catalog = line.getSkuId() == null ? null : skuCatalogMapper.selectById(line.getSkuId());
        String displayName = catalog == null ? null : SkuDisplayNames.of(catalog);
        return new ReplenishmentTaskLineDto(
                line.getLineId(), line.getLineType(), line.getSkuId(),
                displayName, line.getBatchNo(),
                line.getProductionDate(), line.getExpiryDate(), line.getQuantity(),
                line.getSlotId(), line.isApplied()
        );
    }

    private static boolean inDeviceScope(Set<String> allowed, String deviceId) {
        if (deviceId == null) return false;
        return allowed == null || allowed.contains(deviceId);
    }

}
