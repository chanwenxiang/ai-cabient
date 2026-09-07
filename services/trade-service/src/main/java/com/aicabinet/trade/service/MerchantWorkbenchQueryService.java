package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.MerchantDailyTrendDto;
import com.aicabinet.common.dto.MerchantDashboardStatsDto;
import com.aicabinet.common.dto.MerchantTrendDto;
import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.common.dto.SlotDiscrepancyAlertDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商户门户看板 / 趋势 / 待办（原 MerchantPortalService workbench 簇）。
 * Pass 3F：待分账口径与运营侧不同（G1）— 见 {@link #PENDING_SPLIT_STATUSES}。
 */
@Service
public class MerchantWorkbenchQueryService {

    private static final String MERCHANT_SETTLEMENTS_VIEW = "merchant:settlements:view";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_PENDING = "PENDING";
    private static final String MEDIUM = "MEDIUM";

    /**
     * 商户「待分账」状态集（与 {@link MerchantFinanceService} 一致）。
     * 注意：运营 OpsWorkbench 使用另一套（含 FAILED / 不含 PENDING），见 G1。
     */
    public static final List<String> PENDING_SPLIT_STATUSES = List.of(
            STATUS_PENDING, "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED"
    );
    public static final List<String> FAILED_SPLIT_STATUSES = List.of("WECHAT_FAILED", "FAILED");
    private static final int WORKBENCH_ITEM_CAP = 50;

    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final DeviceInfoMapper deviceRepository;
    private final CabinetOrderMapper orderRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final DisputeTicketMapper disputeRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotService deviceSlotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;

    public MerchantWorkbenchQueryService(PermissionService permissionService,
                                         MerchantPortalGuard merchantPortalGuard,
                                         MerchantFeaturePackService merchantFeaturePackService,
                                         DeviceInfoMapper deviceRepository,
                                         CabinetOrderMapper orderRepository,
                                         OrderRevenueSplitMapper splitRepository,
                                         ShoppingSessionMapper sessionRepository,
                                         DisputeTicketMapper disputeRepository,
                                         DeviceSkuInventoryMapper inventoryRepository,
                                         PullOffTaskMapper pullOffTaskRepository,
                                         DeviceSlotService deviceSlotService,
                                         ReplenishmentTaskMapper replenishmentTaskRepository) {
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.deviceRepository = deviceRepository;
        this.orderRepository = orderRepository;
        this.splitRepository = splitRepository;
        this.sessionRepository = sessionRepository;
        this.disputeRepository = disputeRepository;
        this.inventoryRepository = inventoryRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.deviceSlotService = deviceSlotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
    }

    public MerchantDashboardStatsDto getStats(Long userId) {
        merchantPortalGuard.requireAccess(userId);
        List<DeviceInfo> devices = merchantFeaturePackService.allowedDevicesForPack(
                userId, MerchantFeaturePacks.FIELD);
        int online = (int) devices.stream().filter(d -> CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(d.getOnlineStatus())).count();
        int offline = devices.size() - online;

        boolean canFinanceKpi = permissionService.hasAnyPermission(
                userId,
                "merchant:reports:view",
                MERCHANT_SETTLEMENTS_VIEW,
                "merchant:trend:view",
                "merchant:analytics:view");
        if (!canFinanceKpi) {
            return new MerchantDashboardStatsDto(
                    devices.size(), online, offline, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        Set<String> bizDeviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (bizDeviceIds == null) {
            bizDeviceIds = deviceRepository.findAll().stream()
                    .map(DeviceInfo::getDeviceId)
                    .collect(Collectors.toSet());
        }
        long ordersToday = bizDeviceIds.isEmpty() ? 0
                : orderRepository.countByDeviceIdInAndCreatedAtAfter(bizDeviceIds, startOfDay);
        long revenueToday = bizDeviceIds.isEmpty() ? 0
                : orderRepository.sumTotalAmountByDeviceIdInSince(bizDeviceIds, startOfDay);

        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null) {
            merchantIds = Set.of();
        }
        long incomeToday = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdInSince(merchantIds, startOfDay);
        long incomeTotal = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdIn(merchantIds);
        long pendingSplits = merchantIds.isEmpty() ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        long pendingAmount = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        Instant startOfMonth = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long settledMonth = merchantIds.isEmpty() ? 0
                : splitRepository.sumSuccessMerchantCentsByMerchantIdInSince(merchantIds, startOfMonth);
        long failedSplits = merchantIds.isEmpty() ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, FAILED_SPLIT_STATUSES);

        return new MerchantDashboardStatsDto(
                devices.size(), online, offline, ordersToday, revenueToday,
                incomeToday, incomeTotal, pendingSplits, pendingAmount, settledMonth, failedSplits
        );
    }

    @Transactional(readOnly = true)
    public MerchantTrendDto getTrend(Long userId, int days) {
        permissionService.requirePermission(userId, "merchant:trend:view");
        merchantPortalGuard.requireAccess(userId);
        int window = Math.min(Math.max(days, 1), 90);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Set<String> deviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);

        Map<LocalDate, long[]> orderBuckets = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            orderBuckets.put(start.plusDays(i), new long[]{0, 0});
        }
        if (deviceIds != null && !deviceIds.isEmpty()) {
            for (CabinetOrder order : orderRepository.findByDeviceIdInAndCreatedAtAfter(deviceIds, since)) {
                LocalDate day = order.getCreatedAt().atZone(zone).toLocalDate();
                long[] bucket = orderBuckets.get(day);
                if (bucket != null) {
                    bucket[0]++;
                    bucket[1] += order.getTotalAmountCents();
                }
            }
        }

        Map<LocalDate, Long> incomeByDay = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            incomeByDay.put(start.plusDays(i), 0L);
        }
        if (merchantIds != null && !merchantIds.isEmpty()) {
            for (OrderRevenueSplit split : splitRepository.findByMerchantIdInAndCreatedAtAfter(merchantIds, since)) {
                LocalDate day = split.getCreatedAt().atZone(zone).toLocalDate();
                incomeByDay.merge(day, split.getMerchantCents(), Long::sum);
            }
        }

        List<MerchantDailyTrendDto> trendDays = orderBuckets.entrySet().stream()
                .map(e -> new MerchantDailyTrendDto(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1],
                        incomeByDay.getOrDefault(e.getKey(), 0L)))
                .toList();
        return new MerchantTrendDto(trendDays);
    }

    @Transactional(readOnly = true)
    public MerchantWorkbenchDto getWorkbench(Long userId) {
        permissionService.requirePermission(userId, "merchant:alerts:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> deviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        Set<String> bizDeviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (deviceIds != null && deviceIds.isEmpty()
                && bizDeviceIds != null && bizDeviceIds.isEmpty()
                && merchantIds != null && merchantIds.isEmpty()) {
            return new MerchantWorkbenchDto(0, 0, 0, 0, 0, 0, List.of());
        }

        List<OpsActionItemDto> items = new ArrayList<>();
        long openDisputes = appendOpenDisputeItems(items, bizDeviceIds);
        long offline = appendOfflineDeviceItems(items, deviceIds);
        long lowStock = appendLowStockItems(items, deviceIds);
        long expiry = appendExpiryItems(items, deviceIds);
        List<SlotDiscrepancyAlertDto> discrepancies = appendSlotDiscrepancyItems(items, deviceIds, userId);
        appendReplenishmentItems(items, deviceIds);

        long pendingSplits = merchantIds == null ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);

        items.sort(Comparator
                .comparing((OpsActionItemDto i) -> severityRank(i.severity())).reversed()
                .thenComparing(OpsActionItemDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new MerchantWorkbenchDto(
                openDisputes, offline, lowStock, expiry,
                discrepancies.size(), pendingSplits,
                items.stream().limit(100).toList()
        );
    }

    private long appendOpenDisputeItems(List<OpsActionItemDto> items, Set<String> bizDeviceIds) {
        long openDisputes;
        if (bizDeviceIds == null) {
            openDisputes = disputeRepository.countByStatus("OPEN");
        } else if (bizDeviceIds.isEmpty()) {
            openDisputes = 0;
        } else {
            openDisputes = disputeRepository.countOpenByDeviceIds(bizDeviceIds);
        }
        disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(bizDeviceIds, sessionDeviceId(d.getSessionId())))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE", "HIGH", "待审核争议",
                        formatDisputeReason(d.getReason()),
                        sessionDeviceId(d.getSessionId()), d.getSessionId(), d.getTicketId(),
                        null, null, d.getCreatedAt(), d.getSlaDueAt())));
        return openDisputes;
    }

    private long appendOfflineDeviceItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        long offline;
        if (deviceIds == null) {
            offline = deviceRepository.countByOnlineStatusNot(CabinetConstants.DEVICE_ONLINE);
        } else if (deviceIds.isEmpty()) {
            offline = 0;
        } else {
            offline = deviceRepository.countByDeviceIdInAndOnlineStatusNot(deviceIds, CabinetConstants.DEVICE_ONLINE);
        }
        deviceRepository.findByOnlineStatusNot(CabinetConstants.DEVICE_ONLINE, WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(deviceIds, d.getDeviceId()))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DEVICE_OFFLINE", "HIGH", "柜机离线",
                        d.getDeviceName() != null ? d.getDeviceName() : d.getDeviceId(),
                        d.getDeviceId(), null, null, null, null, d.getUpdatedAt(), null)));
        return offline;
    }

    private long appendLowStockItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        long lowStock;
        if (deviceIds == null) {
            lowStock = inventoryRepository.countLowStock();
        } else if (deviceIds.isEmpty()) {
            lowStock = 0;
        } else {
            lowStock = inventoryRepository.countLowStockByDeviceIds(deviceIds);
        }
        inventoryRepository.findLowStockLimit(WORKBENCH_ITEM_CAP).stream()
                .filter(inv -> inDeviceScope(deviceIds, inv.getId().getDeviceId()))
                .forEach(inv -> items.add(new OpsActionItemDto(
                        "LOW_STOCK", MEDIUM, "库存偏低",
                        "SKU " + inv.getId().getSkuId() + " 当前 " + inv.getQuantity()
                                + " / 阈值 " + inv.getLowThreshold(),
                        inv.getId().getDeviceId(), null, null, inv.getId().getSkuId(),
                        null, inv.getUpdatedAt(), null)));
        return lowStock;
    }

    private long appendExpiryItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        long expiry;
        if (deviceIds == null) {
            expiry = pullOffTaskRepository.countByStatus("OPEN");
        } else if (deviceIds.isEmpty()) {
            expiry = 0;
        } else {
            expiry = pullOffTaskRepository.countByStatusAndDeviceIdIn("OPEN", deviceIds);
        }
        pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(task -> inDeviceScope(deviceIds, task.getDeviceId()))
                .forEach(task -> items.add(new OpsActionItemDto(
                        "EXPIRY", MEDIUM, "临期/过期下架",
                        "SKU " + task.getSkuId() + " · " + task.getReason(),
                        task.getDeviceId(), null, null, task.getSkuId(),
                        task.getTaskId(), task.getCreatedAt(), null)));
        return expiry;
    }

    private List<SlotDiscrepancyAlertDto> appendSlotDiscrepancyItems(List<OpsActionItemDto> items,
                                                                     Set<String> deviceIds, Long userId) {
        List<SlotDiscrepancyAlertDto> discrepancies = deviceSlotService.listDiscrepancyAlerts(userId, null).stream()
                .filter(a -> inDeviceScope(deviceIds, a.deviceId()))
                .limit(WORKBENCH_ITEM_CAP)
                .toList();
        discrepancies.forEach(a -> items.add(new OpsActionItemDto(
                "SLOT_DISCREPANCY", MEDIUM, "货道账实差异",
                a.slotCode() + " 账面 " + a.bookQty() + " 实测 " + a.physicalQty(),
                a.deviceId(), null, null, a.assignedSkuId(),
                null, a.lastPhysicalAt(), null)));
        return discrepancies;
    }

    private void appendReplenishmentItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), WORKBENCH_ITEM_CAP).stream()
                .filter(t -> inDeviceScope(deviceIds, t.getDeviceId()))
                .forEach(t -> items.add(new OpsActionItemDto(
                        "REPLENISHMENT", MEDIUM, "补货任务进行中",
                        "状态 " + replenishmentStatusLabel(t.getStatus())
                                + (t.getNotes() != null ? " · " + t.getNotes() : ""),
                        t.getDeviceId(), null, null, null, t.getTaskId(), t.getCreatedAt(), null)));
    }

    private String sessionDeviceId(String sessionId) {
        return sessionRepository.findById(sessionId).map(ShoppingSession::getDeviceId).orElse(null);
    }

    private static boolean inDeviceScope(Set<String> allowed, String deviceId) {
        if (deviceId == null) return false;
        return allowed == null || allowed.contains(deviceId);
    }

    private static int severityRank(String severity) {
        return switch (severity != null ? severity : "") {
            case "HIGH" -> 3;
            case MEDIUM -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static String formatDisputeReason(String reason) {
        if (reason == null || reason.isBlank()) return "识别结果需人工审核";
        return reason.trim();
    }

    private static String replenishmentStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status.toUpperCase()) {
            case STATUS_PENDING -> "待处理";
            case STATUS_IN_PROGRESS -> "进行中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

}
