package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.MerchantDailyTrendDto;
import com.aicabinet.common.dto.MerchantDashboardStatsDto;
import com.aicabinet.common.dto.MerchantTrendDto;
import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
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
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.support.SkuDisplayNames;
import com.aicabinet.trade.support.SkuOnShelf;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final SkuCatalogMapper skuCatalogRepository;

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
                                         ReplenishmentTaskMapper replenishmentTaskRepository,
                                         SkuCatalogMapper skuCatalogRepository) {
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
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.skuCatalogRepository = skuCatalogRepository;
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
        appendReplenishmentItems(items, deviceIds);

        long pendingSplits = merchantIds == null ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);

        items.sort(Comparator
                .comparing((OpsActionItemDto i) -> severityRank(i.severity())).reversed()
                .thenComparing(OpsActionItemDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        long refundOrders = merchantIds == null
                ? orderRepository.selectCount(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aicabinet.trade.domain.CabinetOrder>lambdaQuery()
                                .eq(com.aicabinet.trade.domain.CabinetOrder::getStatus, "REFUNDED"))
                : merchantIds.isEmpty()
                        ? 0
                        : orderRepository.selectCount(
                                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aicabinet.trade.domain.CabinetOrder>lambdaQuery()
                                        .eq(com.aicabinet.trade.domain.CabinetOrder::getStatus, "REFUNDED")
                                        .in(com.aicabinet.trade.domain.CabinetOrder::getMerchantId, merchantIds));

        return new MerchantWorkbenchDto(
                openDisputes, offline, lowStock, expiry,
                pendingSplits,
                refundOrders,
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
        // 设备范围下推进 SQL：旧写法「取 CAP(50) 条 OPEN 再 filter(session→device)」的窗口是按
        // createdAt **全局**取的，域受限账号自己的工单排在窗口外就一条都看不到（B13 同族）。
        // 争议是**含资金**的待办，漏看的代价比补货更高。顺带消掉逐行 findById 的 N+1。
        disputeRepository.findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", bizDeviceIds, WORKBENCH_ITEM_CAP)
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE", "HIGH", "待审核争议",
                        formatDisputeReason(d.getReason()),
                        d.getSessionDeviceId(), d.getSessionId(), d.getTicketId(),
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
        // 设备范围下推进 SQL（计数早已带范围）。旧写法取全局最早 CAP(50) 台再内存过滤，
        // 域受限账号自己的离线柜会被别家的挤出窗口（B13 同族）。
        deviceRepository.findByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
                        deviceIds, CabinetConstants.DEVICE_ONLINE, WORKBENCH_ITEM_CAP)
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DEVICE_OFFLINE", "HIGH", "柜机离线",
                        d.getDeviceName() != null ? d.getDeviceName() : d.getDeviceId(),
                        d.getDeviceId(), null, null, null, null, d.getUpdatedAt(), null)));
        return offline;
    }

    // 包内可见：供同包测试直接验证低库存口径（台账阈值），不必走整条 getWorkbench
    long appendLowStockItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        // 台账口径 quantity <= device_sku_inventory.low_threshold（后台「库存台账」逐 SKU 维护的阈值，
        // 与备货建议 suggestFromLowStock 同源）。批 3b 第二十刀：货道柜分支整体退场，不再按柜型分流。
        List<String> scopeIds = deviceIds != null ? new ArrayList<>(deviceIds)
                : deviceRepository.findAllOrderByDeviceIdAsc().stream()
                        .map(DeviceInfo::getDeviceId).toList();
        return appendLedgerLowStock(items, scopeIds);
    }

    /** 台账口径（后台阈值），条目粒度=柜×SKU。 */
    private long appendLedgerLowStock(List<OpsActionItemDto> items, List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        List<DeviceSkuInventory> rows = inventoryRepository.findLowStockByDeviceIds(deviceIds);
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> skuIds = rows.stream().map(DeviceSkuInventory::getSkuId).distinct().toList();
        Map<String, String> nameBySku = new LinkedHashMap<>();
        Set<String> onShelf = new HashSet<>();
        for (var sku : skuCatalogRepository.findBySkuIds(skuIds)) {
            nameBySku.put(sku.getSkuId(), SkuDisplayNames.of(sku));
            if (SkuOnShelf.isOnShelf(sku)) {
                onShelf.add(sku.getSkuId());
            }
        }
        // 低库存面只算在架 SKU（2026-10-30 拍板，与商户/运营库存页、洞察同一条判据）：
        // 下架行清零后会被读成「库存偏低」，连低库存提醒也会替商户催它已放弃的商品。
        rows = rows.stream().filter(row -> onShelf.contains(row.getSkuId())).toList();
        if (rows.isEmpty()) {
            return 0;
        }
        for (DeviceSkuInventory row : rows) {
            if (items.size() < WORKBENCH_ITEM_CAP) {
                String name = nameBySku.getOrDefault(row.getSkuId(), row.getSkuId());
                items.add(new OpsActionItemDto(
                        "LOW_STOCK", MEDIUM, "库存偏低",
                        name + " 当前 " + row.getQuantity() + " / 上限 " + row.getCapacity()
                                + "（阈值 " + row.getLowThreshold() + " 件）",
                        row.getDeviceId(), null, null, row.getSkuId(), null, null, null));
            }
        }
        return rows.size();
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
        // 设备范围下推进 SQL（计数早已带范围）。临期下架是**要人动手**的待办，
        // 被窗口挤掉就会一直挂着过期商品继续卖（B13 同族）。
        pullOffTaskRepository.findByStatusAndDeviceIdInOrderByCreatedAtDesc(
                        "OPEN", deviceIds, WORKBENCH_ITEM_CAP)
                .forEach(task -> items.add(new OpsActionItemDto(
                        "EXPIRY", MEDIUM, "临期/过期下架",
                        "SKU " + task.getSkuId() + " · " + task.getReason(),
                        task.getDeviceId(), null, null, task.getSkuId(),
                        task.getTaskId(), task.getCreatedAt(), null)));
        return expiry;
    }

    private void appendReplenishmentItems(List<OpsActionItemDto> items, Set<String> deviceIds) {
        // 设备范围下推进 SQL。旧写法「取 CAP(50) 条再 filter(inDeviceScope)」的窗口是全局按 createdAt 取的，
        // 域受限账号自己的任务排在第 51 条之后就会被滤空 —— 面板显示「没有待办」而任务还挂着（B13 同族）。
        replenishmentTaskRepository.findWorkbenchTasks(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), deviceIds, WORKBENCH_ITEM_CAP)
                .forEach(t -> items.add(new OpsActionItemDto(
                        "REPLENISHMENT", MEDIUM, "补货任务进行中",
                        replenishmentTodoDetail(t.getStatus(), t.getNotes()),
                        t.getDeviceId(), null, null, null, t.getTaskId(), t.getCreatedAt(), null)));
    }

    /** notes 里的 seq=/dist= 是路线规划内部字段；对外展示为「路线第 N 站（距上一站 X 米）」。 */
    private static String replenishmentTodoDetail(String status, String notes) {
        String label = replenishmentStatusLabel(status);
        if (notes == null || notes.isBlank()) {
            return "状态 " + label;
        }
        var seq = java.util.regex.Pattern.compile("seq=([0-9]+)").matcher(notes);
        var dist = java.util.regex.Pattern.compile("dist=([0-9]+)m").matcher(notes);
        if (seq.find()) {
            String stop = "路线第 " + seq.group(1) + " 站";
            if (dist.find()) {
                long meters = Long.parseLong(dist.group(1));
                return "状态 " + label + " · " + stop
                        + (meters > 0 ? "（距上一站 " + meters + " 米）" : "");
            }
            return "状态 " + label + " · " + stop;
        }
        return "状态 " + label;
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
