package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdminStatsDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.common.dto.OpsDashboardBundleDto;
import com.aicabinet.common.dto.OpsWorkbenchDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import com.aicabinet.trade.support.SkuDisplayNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营工作台 / 看板统计（原 AdminDashboardService workbench/stats 簇）。
 * Pass 3F PR-E：从神类拆出，门面仍可委托本类。
 */
@Service
public class OpsWorkbenchQueryService {

    private static final String PERM_OPS_DASHBOARD_VIEW = "ops:dashboard:view";
    private static final String PERM_OPS_ANALYTICS_VIEW = "ops:analytics:view";
    private static final String WECHAT_FAILED = "WECHAT_FAILED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String LEDGER_ONLY = "LEDGER_ONLY";
    private static final String IN_TRANSIT = "IN_TRANSIT";
    private static final String CRITICAL = "CRITICAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String MEDIUM = "MEDIUM";

    /** 工作台「待支付」与订单页 overdue=1 对齐：超过该分钟仍 PENDING 计入。 */
    public static final int UNPAID_OPS_OVERDUE_MINUTES = OpsSessionOrderQueryService.UNPAID_OPS_OVERDUE_MINUTES;

    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    /** 运营侧「待分账/异常分账」口径（与商户 PENDING_SPLIT 不同，见 G1）。 */
    public static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "ACCRUED", LEDGER_ONLY, CabinetConstants.ORDER_STATUS_FAILED, WECHAT_FAILED
    );
    static final List<String> SPLIT_EXCEPTION_STATUSES = List.of(
            CabinetConstants.ORDER_STATUS_FAILED, WECHAT_FAILED, LEDGER_ONLY);
    private static final long STALE_SESSION_MINUTES = 30;
    private static final long IN_TRANSIT_OVERDUE_HOURS = 24;
    /** 工作台待办每类最多展示条数，避免全表加载。 */
    private static final int WORKBENCH_ITEM_CAP = 20;

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final DisputeTicketMapper disputeRepository;
    private final DisputeSlaService disputeSlaService;
    private final SlaMetricsService slaMetricsService;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final InventoryLotService inventoryLotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final PaymentReconciliationMapper reconciliationRepository;
    private final WarehouseInTransitMapper inTransitRepository;
    private final OpsExceptionMapper exceptionRepository;
    private final OpsSessionOrderQueryService sessionOrderQueryService;
    private final SkuCatalogMapper skuCatalogRepository;

    public OpsWorkbenchQueryService(PermissionService permissionService,
                                    MerchantScopeService merchantScopeService,
                                    DeviceInfoMapper deviceRepository,
                                    ShoppingSessionMapper sessionRepository,
                                    CabinetOrderMapper orderRepository,
                                    DisputeTicketMapper disputeRepository,
                                    DisputeSlaService disputeSlaService,
                                    SlaMetricsService slaMetricsService,
                                    DeviceSkuInventoryMapper inventoryRepository,
                                    OrderRevenueSplitMapper splitRepository,
                                    InventoryLotService inventoryLotService,
                                    ReplenishmentTaskMapper replenishmentTaskRepository,
                                    PaymentReconciliationMapper reconciliationRepository,
                                    WarehouseInTransitMapper inTransitRepository,
                                    OpsExceptionMapper exceptionRepository,
                                    OpsSessionOrderQueryService sessionOrderQueryService,
                                    SkuCatalogMapper skuCatalogRepository) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.disputeRepository = disputeRepository;
        this.disputeSlaService = disputeSlaService;
        this.slaMetricsService = slaMetricsService;
        this.inventoryRepository = inventoryRepository;
        this.splitRepository = splitRepository;
        this.inventoryLotService = inventoryLotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.inTransitRepository = inTransitRepository;
        this.exceptionRepository = exceptionRepository;
        this.sessionOrderQueryService = sessionOrderQueryService;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    @Transactional(readOnly = true)
    public OpsDashboardBundleDto dashboardBundle(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DASHBOARD_VIEW);
        AdminStatsDto stats = stats(operatorId);
        OpsWorkbenchDto wb = workbench(operatorId);
        long open = exceptionRepository
                .findByStatusOrderByCreatedAtDesc("OPEN", PageRequest.of(0, 1))
                .getTotalElements();
        return new OpsDashboardBundleDto(stats, wb, open);
    }

    public AdminStatsDto stats(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return emptyStats();
        }
        if (scopedDevices == null) {
            return globalStats(todayStart, since24h, operatorId);
        }
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId).stream()
                .filter(OpsWorkbenchQueryService::isDeployedDevice)
                .toList();
        long deviceTotal = devices.size();
        long deviceOnline = devices.stream()
                .filter(d -> CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(d.getOnlineStatus()))
                .count();
        long completed24h = sessionRepository.countByDeviceIdInAndStateAndUpdatedAtAfter(
                scopedDevices, SessionState.COMPLETED, since24h);
        long disputed24h = sessionRepository.countByDeviceIdInAndStateAndUpdatedAtAfter(
                scopedDevices, SessionState.DISPUTED, since24h);
        long closed24h = completed24h + disputed24h;
        double recognitionAutoRate = closed24h > 0 ? (double) completed24h / closed24h : 1.0;
        double disputeRate = closed24h > 0 ? (double) disputed24h / closed24h : 0.0;
        var slaRealtime = slaMetricsService.realtimeMetrics(operatorId);
        long sessionActive = sessionRepository.countByDeviceIdInAndStateIn(scopedDevices, ACTIVE_STATES);
        long deviceOccupied = countOccupiedDevices(scopedDevices);
        return new AdminStatsDto(
                deviceTotal,
                deviceOnline,
                sessionActive,
                deviceOccupied,
                sessionRepository.countByDeviceIdInAndCreatedAtAfter(scopedDevices, todayStart),
                orderRepository.countByDeviceIdInAndCreatedAtAfter(scopedDevices, todayStart),
                orderRepository.sumTotalAmountByDeviceIdInSince(scopedDevices, todayStart),
                orderRepository.countByDeviceIdIn(scopedDevices),
                orderRepository.sumTotalAmountByDeviceIdIn(scopedDevices),
                disputeRepository.countOpenByDeviceIds(scopedDevices),
                disputeSlaService.countOverdue(),
                disputeSlaService.countNearSla(),
                slaRealtime.doorSuccessRate24h(),
                disputeRate,
                recognitionAutoRate,
                inventoryRepository.countLowStock(),
                splitRepository.countByStatusIn(PENDING_SPLIT_STATUSES),
                inventoryLotService.countNearExpiryLots(),
                inventoryLotService.countExpiredLotsWithStock(),
                inventoryLotService.countOpenPullOffTasks()
        );
    }

    @Transactional(readOnly = true)
    public OpsWorkbenchDto workbench(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DASHBOARD_VIEW);
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        collectDisputeActionItems(scopedDevices, items);
        collectOfflineDeviceItems(scopedDevices, items);
        collectLowStockItems(scopedDevices, items);
        collectReplenishmentActionItems(scopedDevices, items);
        collectStaleSessionItems(scopedDevices, items);
        collectReconMismatchItems(items);
        collectSplitExceptionItems(scopedDevices, items);
        collectInTransitOverdueItems(scopedDevices, items);

        items.sort(java.util.Comparator
                .comparingInt((OpsActionItemDto item) -> severityRank(item.severity()))
                .thenComparing(item -> item.dueAt() != null ? item.dueAt() : Instant.MAX));

        return buildWorkbenchDto(operatorId, scopedDevices, items);
    }

    // 以下 collect* / count* 为包内可见：供同包测试直接验「设备范围是否下推」，
    // 不必构造整条 workbench（同 collectLowStockItems 先例）。
    void collectDisputeActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        // 设备范围下推进 SQL（与 countOpenDisputes 同一判据）。旧写法「取 CAP(20) 条 OPEN 再
        // filter(session→device)」的窗口全局按 createdAt 取，域受限账号的工单会被别家的挤出窗口
        // —— 面板显示「没有待办」而工单还挂着，且争议是含资金待办（B13 同族）。
        disputeRepository.findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", scopedDevices, WORKBENCH_ITEM_CAP)
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE",
                        disputeSeverity(d),
                        "待审核争议",
                        formatDisputeReasonText(d.getReason()),
                        d.getSessionDeviceId(),
                        d.getSessionId(),
                        d.getTicketId(),
                        null,
                        null,
                        d.getCreatedAt(),
                        d.getSlaDueAt()
                )));
    }

    void collectOfflineDeviceItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        // 「设备范围」与「投放柜口径」两维一起下推（lifecycle 判据见 mapper 注释：null 归 DEPLOYED）。
        // 旧写法只把范围留在内存过滤、lifecycle 也在内存过滤 —— 窗口会被 INBOUND/退役柜占满，
        // 真正离线的投放柜反而挤不进来，等于没修（B13 同族）。
        deviceRepository.findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
                        scopedDevices, CabinetConstants.DEVICE_ONLINE, WORKBENCH_ITEM_CAP)
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DEVICE_OFFLINE",
                        offlineSeverity(d),
                        "设备离线",
                        d.getDeviceName() != null && !d.getDeviceName().isBlank()
                                ? d.getDeviceName() : d.getDeviceId(),
                        d.getDeviceId(),
                        null,
                        null,
                        null,
                        null,
                        d.getUpdatedAt(),
                        null
                )));
    }

    // 包内可见：供同包测试直接验证低库存口径（台账阈值；与商户端 MerchantWorkbenchQueryService 同规则）
    void collectLowStockItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        // 台账口径 quantity <= low_threshold（后台阈值），与同页 lowStock 指标（countLowStock）同源。
        // 批 3b 第二十刀：货道柜分支整体退场，不再按柜型分流。
        List<String> deviceIds = new ArrayList<>();
        resolveShortageDeviceIds(scopedDevices).forEach(deviceIds::add);
        collectLedgerLowStockItems(deviceIds, items);
    }

    private void collectLedgerLowStockItems(List<String> deviceIds, List<OpsActionItemDto> items) {
        if (deviceIds.isEmpty() || items.size() >= WORKBENCH_ITEM_CAP) {
            return;
        }
        List<DeviceSkuInventory> rows = inventoryRepository.findLowStockByDeviceIds(deviceIds);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> nameBySku = new LinkedHashMap<>();
        List<String> skuIds = rows.stream().map(DeviceSkuInventory::getSkuId).distinct().toList();
        for (var sku : skuCatalogRepository.findBySkuIds(skuIds)) {
            nameBySku.put(sku.getSkuId(), SkuDisplayNames.of(sku));
        }
        for (DeviceSkuInventory row : rows) {
            if (items.size() >= WORKBENCH_ITEM_CAP) {
                return;
            }
            String name = nameBySku.getOrDefault(row.getSkuId(), row.getSkuId());
            items.add(new OpsActionItemDto(
                    "LOW_STOCK",
                    MEDIUM,
                    "库存偏低",
                    name + " 当前 " + row.getQuantity() + " / 上限 " + row.getCapacity()
                            + "（阈值 " + row.getLowThreshold() + " 件）",
                    row.getDeviceId(),
                    null,
                    null,
                    row.getSkuId(),
                    null,
                    null,
                    null
            ));
        }
    }


    /** 缺货巡检的设备范围：scoped=null（超管全量）时退化为全设备遍历（现有库规模可接受）。 */
    private Iterable<String> resolveShortageDeviceIds(Set<String> scopedDevices) {
        if (scopedDevices != null) {
            return scopedDevices;
        }
        return deviceRepository.findAllOrderByDeviceIdAsc().stream()
                .map(DeviceInfo::getDeviceId)
                .toList();
    }

    private void collectReplenishmentActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        // 设备范围下推进 SQL：旧写法「取 CAP(20) 条再 filter(inDeviceScope)」的窗口是全局按 createdAt 取的，
        // 域受限账号自己的任务排在窗口之外就一条都看不到（B13 同族）。
        replenishmentTaskRepository.findWorkbenchTasks(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), scopedDevices, WORKBENCH_ITEM_CAP)
                .forEach(t -> items.add(new OpsActionItemDto(
                        "REPLENISHMENT",
                        MEDIUM,
                        "补货任务待处理",
                        "状态：" + replenishStatusLabel(t.getStatus()),
                        t.getDeviceId(),
                        null,
                        null,
                        null,
                        t.getTaskId(),
                        t.getCreatedAt(),
                        null
                )));
    }

    private void collectStaleSessionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        findStaleSessions(scopedDevices).forEach(s -> items.add(new OpsActionItemDto(
                "SESSION_STALE",
                staleSessionSeverity(s),
                "购物会话可能超时",
                "状态 " + s.getState(),
                s.getDeviceId(),
                s.getSessionId(),
                null,
                null,
                null,
                s.getCreatedAt(),
                // updated_at 允许为 null（超时判据里「updated_at IS NULL 且 created_at < cutoff」算超时），
                // 直接 .plus() 会 NPE —— 兜底用 createdAt，与判据同源。
                (s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt())
                        .plus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES)
        )));
    }

    private void collectReconMismatchItems(List<OpsActionItemDto> items) {
        reconciliationRepository.findTop10ByStatusOrderByCompletedAtDesc("MISMATCH")
                .forEach(r -> items.add(new OpsActionItemDto(
                        "RECON_MISMATCH",
                        Math.abs(r.getDiffCents()) > 0 ? "HIGH" : MEDIUM,
                        "对账存在差异",
                        "日期 " + r.getReconDate() + " · 渠道 " + payChannelLabel(r.getChannel())
                                + " · 差额 ¥" + String.format("%.2f", r.getDiffCents() / 100.0)
                                + " · 未匹配 " + r.getUnmatchedCount() + " 笔",
                        null,
                        null,
                        null,
                        null,
                        null,
                        r.getCompletedAt() != null ? r.getCompletedAt() : r.getCreatedAt(),
                        null
                )));
    }

    void collectSplitExceptionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        // 设备范围下推进 SQL，保留「每状态 Top-N 再按展示上限截断」的形状。
        // 旧写法 findTop20(status) 的窗口是全局最早的 20 条，域受限账号自己的失败分账排在外面
        // 就一条都不显示 —— 分账失败要主动跟进才回款，漏看比离线柜更贵（B13 同族）。
        SPLIT_EXCEPTION_STATUSES.forEach(status ->
                splitRepository.findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc(status, scopedDevices, 20).stream()
                        .limit(5)
                        .forEach(s -> items.add(new OpsActionItemDto(
                                "SPLIT_EXCEPTION",
                                CabinetConstants.ORDER_STATUS_FAILED.equalsIgnoreCase(s.getStatus())
                                        || WECHAT_FAILED.equalsIgnoreCase(s.getStatus())
                                        ? "HIGH" : MEDIUM,
                                "分账待跟进",
                                "订单 " + s.getOrderId() + " · 状态 " + splitStatusLabel(s.getStatus())
                                        + (s.getFailureReason() != null && !s.getFailureReason().isBlank()
                                        ? " · 原因 " + s.getFailureReason() : ""),
                                s.getDeviceId(),
                                null,
                                null,
                                null,
                                null,
                                s.getCreatedAt(),
                                s.getSettleAfter() != null
                                        ? s.getSettleAfter().atStartOfDay(ZoneId.systemDefault()).toInstant()
                                        : null
                        )))
        );
    }

    void collectInTransitOverdueItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        Instant transitCutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        // 取数上限仍是 500 **行**：下面按出库单聚合，行数≠告警条数，不能压到展示上限 20。
        List<WarehouseInTransit> overdueLines = inTransitRepository
                .findByStatusAndCreatedAtBeforeAndDeviceIdInOrderByCreatedAtAsc(
                        IN_TRANSIT, transitCutoff, scopedDevices, 500);
        items.addAll(aggregateInTransitOverdueActionItems(overdueLines));
    }

    /**
     * 将超时在途行按出库单聚合为工作台告警；无出库单号的行仍逐条保留。
     * package-visible 便于单测。
     */
    public static List<OpsActionItemDto> aggregateInTransitOverdueActionItems(List<WarehouseInTransit> overdueLines) {
        if (overdueLines == null || overdueLines.isEmpty()) {
            return List.of();
        }
        Map<Long, List<WarehouseInTransit>> byOutbound = new java.util.LinkedHashMap<>();
        List<WarehouseInTransit> orphans = new java.util.ArrayList<>();
        for (WarehouseInTransit line : overdueLines) {
            if (line.getOutboundId() == null) {
                orphans.add(line);
                continue;
            }
            byOutbound.computeIfAbsent(line.getOutboundId(), key -> new java.util.ArrayList<>()).add(line);
        }
        List<OpsActionItemDto> aggregated = new java.util.ArrayList<>();
        for (Map.Entry<Long, List<WarehouseInTransit>> entry : byOutbound.entrySet()) {
            if (aggregated.size() >= WORKBENCH_ITEM_CAP) {
                break;
            }
            List<WarehouseInTransit> lines = entry.getValue();
            WarehouseInTransit first = lines.get(0);
            Instant oldestCreated = lines.stream()
                    .map(WarehouseInTransit::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(first.getCreatedAt());
            long skuCount = lines.stream()
                    .map(WarehouseInTransit::getSkuId)
                    .filter(sku -> sku != null && !sku.isBlank())
                    .distinct()
                    .count();
            int quantitySum = lines.stream().mapToInt(WarehouseInTransit::getQuantity).sum();
            aggregated.add(new OpsActionItemDto(
                    "IN_TRANSIT_OVERDUE",
                    "HIGH",
                    "补货未上柜超时",
                    "出库单 " + entry.getKey()
                            + " · " + skuCount + " 个 SKU"
                            + " · 共 " + quantitySum + " 件",
                    first.getDeviceId(),
                    null,
                    null,
                    null,
                    entry.getKey(),
                    oldestCreated,
                    oldestCreated != null
                            ? oldestCreated.plus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS)
                            : null
            ));
        }
        for (WarehouseInTransit orphan : orphans) {
            if (aggregated.size() >= WORKBENCH_ITEM_CAP) {
                break;
            }
            aggregated.add(new OpsActionItemDto(
                    "IN_TRANSIT_OVERDUE",
                    "HIGH",
                    "补货未上柜超时",
                    "商品 " + orphan.getSkuId()
                            + " · 批次 " + orphan.getBatchNo()
                            + " · 数量 " + orphan.getQuantity(),
                    orphan.getDeviceId(),
                    null,
                    null,
                    orphan.getSkuId(),
                    orphan.getTransitId(),
                    orphan.getCreatedAt(),
                    orphan.getCreatedAt() != null
                            ? orphan.getCreatedAt().plus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS)
                            : null
            ));
        }
        return aggregated;
    }

    long countInTransitOverdue(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return 0;
        }
        // 与告警明细一致：按出库单去重（无出库单号时按在途行），设备范围下推进 SQL。
        // 旧写法取 500 行样本在内存里去重计数 —— 超时行超过 500 时数字封顶（与明细同时失真）。
        return inTransitRepository.countDistinctOutboundOverdue(IN_TRANSIT, cutoff, scopedDevices);
    }

    private OpsWorkbenchDto buildWorkbenchDto(
            Long operatorId, Set<String> scopedDevices, List<OpsActionItemDto> items) {
        List<DeviceInfo> scopedDeviceList = merchantScopeService.allowedDevices(operatorId);
        long devicesSalesLocked = scopedDeviceList.stream().filter(DeviceInfo::salesLockedEnabled).count();
        return new OpsWorkbenchDto(
                countOpenDisputes(scopedDevices),
                disputeSlaService.countOverdue(),
                countOfflineDevices(scopedDevices),
                countLowStock(scopedDevices),
                countPendingReplenishments(scopedDevices),
                countStaleSessions(scopedDevices),
                reconciliationRepository.countByStatus("MISMATCH"),
                countSplitExceptions(scopedDevices),
                countInTransitOverdue(scopedDevices),
                items.stream().limit(100).toList(),
                scopedDeviceList.size() - devicesSalesLocked,
                devicesSalesLocked,
                countOverdueUnpaidOrders(operatorId)
        );
    }

    private long countOverdueUnpaidOrders(Long operatorId) {
        return sessionOrderQueryService.countOverdueUnpaidOrders(operatorId);
    }

    private AdminStatsDto globalStats(Instant todayStart, Instant since24h, Long operatorId) {
        long completed24h = sessionRepository.countByStateAndUpdatedAtAfter(SessionState.COMPLETED, since24h);
        long disputed24h = sessionRepository.countByStateAndUpdatedAtAfter(SessionState.DISPUTED, since24h);
        long closed24h = completed24h + disputed24h;
        double recognitionAutoRate = closed24h > 0 ? (double) completed24h / closed24h : 1.0;
        double disputeRate = closed24h > 0 ? (double) disputed24h / closed24h : 0.0;
        var slaRealtime = slaMetricsService.realtimeMetrics(operatorId);
        long sessionActive = sessionRepository.countByStateIn(ACTIVE_STATES);
        long deviceOccupied = countOccupiedDevices(null);
        // 在线率分子/分母仅投放柜（与离线待办 #193、库存健康默认 DEPLOYED 对齐）
        List<DeviceInfo> deployedDevices = deviceRepository.findAllOrderByDeviceIdAsc().stream()
                .filter(OpsWorkbenchQueryService::isDeployedDevice)
                .toList();
        long deviceTotal = deployedDevices.size();
        long deviceOnline = deployedDevices.stream()
                .filter(d -> CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(d.getOnlineStatus()))
                .count();
        return new AdminStatsDto(
                deviceTotal,
                deviceOnline,
                sessionActive,
                deviceOccupied,
                sessionRepository.countByCreatedAtAfter(todayStart),
                orderRepository.countByCreatedAtAfter(todayStart),
                orderRepository.sumTotalAmountSince(todayStart),
                orderRepository.count(),
                orderRepository.sumTotalAmount(),
                disputeRepository.countByStatus("OPEN"),
                disputeSlaService.countOverdue(),
                disputeSlaService.countNearSla(),
                slaRealtime.doorSuccessRate24h(),
                disputeRate,
                recognitionAutoRate,
                inventoryRepository.countLowStock(),
                splitRepository.countByStatusIn(PENDING_SPLIT_STATUSES),
                inventoryLotService.countNearExpiryLots(),
                inventoryLotService.countExpiredLotsWithStock(),
                inventoryLotService.countOpenPullOffTasks()
        );
    }

    private static AdminStatsDto emptyStats() {
        return new AdminStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0, 0.0, 1.0, 0, 0, 0, 0, 0);
    }

    // 包内可见：供同包测试直接验证「占柜」口径（与 isRestockingNow 同源），不必走整条 getWorkbench
    long countOccupiedDevices(Set<String> scopedDevices) {
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return 0;
        }
        Set<String> occupied = new HashSet<>();
        long sessionDevices = scopedDevices == null
                ? sessionRepository.countDistinctDeviceIdByStateIn(ACTIVE_STATES)
                : sessionRepository.countDistinctDeviceIdByDeviceIdInAndStateIn(scopedDevices, ACTIVE_STATES);
        // 补货占柜：只算**已签到**的 IN_PROGRESS（判据与 isRestockingNow 同源）。
        // 出库明细生成即置 IN_PROGRESS 的行人还没到店，把它算成「占柜」会让运营看到虚高的占用数，
        // 也和「挡开门」的口径对不上（那边逐柜查的就是 isRestockingNow）。
        // 设备范围下推 + 不设窗口：旧写法「取 500 条再按范围过滤」在域受限账号下会把自己的任务挤掉（B13 同族）。
        occupied.addAll(replenishmentTaskRepository.findRestockingNowDeviceIds(scopedDevices));
        // 无法无会话设备列表精确并集时：取「会话占柜」与「补货占柜」的上界近似
        // （补货设备通常也有补货会话；若仅有任务无会话，用 max 会略低估，可接受）
        return Math.max(sessionDevices, occupied.size());
    }

    long countOpenDisputes(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return disputeRepository.countByStatus("OPEN");
        }
        if (scopedDevices.isEmpty()) {
            return 0;
        }
        // 计数也必须下推：旧写法「取 500 条 OPEN 样本再内存过滤」在 OPEN 工单超过 500 条时
        // 数字直接封顶 500 —— 面板上的数字本身是失真的，比列表少几条更误导。
        return disputeRepository.countOpenByDeviceIds(scopedDevices);
    }

    long countOfflineDevices(Set<String> scopedDevices) {
        // 与告警明细同一谓词（设备范围 + 投放柜 + 离线），全部下推；
        // 旧写法是「全表拉回内存 filter count」——行数随设备总量线性涨，且与下推后的列表判据容易走偏。
        return deviceRepository.countDeployedByDeviceIdInAndOnlineStatusNot(
                scopedDevices, CabinetConstants.DEVICE_ONLINE);
    }

    /** 运营工作台设备类待办只认投放柜（与库存健康默认 lifecycle=DEPLOYED 对齐）。 */
    static boolean isDeployedDevice(DeviceInfo device) {
        return "DEPLOYED".equals(DeviceAssetService.normalizeLifecycle(
                device == null ? null : device.getLifecycleStatus()));
    }

    private long countLowStock(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return inventoryRepository.countLowStock();
        }
        return inventoryRepository.countLowStockByDeviceIds(scopedDevices);
    }

    private long countPendingReplenishments(Set<String> scopedDevices) {
        List<String> statuses = List.of(STATUS_PENDING, STATUS_IN_PROGRESS);
        if (scopedDevices == null) {
            return replenishmentTaskRepository.countByStatusIn(statuses);
        }
        return replenishmentTaskRepository.countByStatusInAndDeviceIdIn(statuses, scopedDevices);
    }

    private List<ShoppingSession> findStaleSessions(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES);
        // 设备范围下推进 SQL，超时判据与 countStaleSessions 逐字一致（含 updated_at IS NULL 分支）。
        // 旧写法「取全局最早 CAP(20) 条再内存过滤」会把域受限账号的超时会话挤出窗口（B13 同族）。
        return sessionRepository.findByStateInAndDeviceIdInAndUpdatedAtBefore(
                ACTIVE_STATES, scopedDevices, cutoff, WORKBENCH_ITEM_CAP);
    }

    private long countStaleSessions(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES);
        if (scopedDevices == null) {
            return sessionRepository.countByStateInAndUpdatedAtBefore(ACTIVE_STATES, cutoff);
        }
        return sessionRepository.countByDeviceIdInAndStateInAndUpdatedAtBefore(
                scopedDevices, ACTIVE_STATES, cutoff);
    }

    private long countSplitExceptions(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return splitRepository.countByStatusIn(SPLIT_EXCEPTION_STATUSES);
        }
        return splitRepository.countByStatusInAndDeviceIdIn(SPLIT_EXCEPTION_STATUSES, scopedDevices);
    }

    private static String staleSessionSeverity(ShoppingSession session) {
        if (session.getState() == SessionState.WAITING_UPLOAD) {
            return "HIGH";
        }
        if (session.getState() == SessionState.OPENING || session.getState() == SessionState.SETTLING) {
            return "HIGH";
        }
        return MEDIUM;
    }

    private static String disputeSeverity(DisputeTicket ticket) {
        if (ticket.getSlaDueAt() != null && !ticket.getSlaDueAt().isAfter(Instant.now())) {
            return CRITICAL;
        }
        if ("URGENT".equalsIgnoreCase(ticket.getPriority())) {
            return CRITICAL;
        }
        if ("HIGH".equalsIgnoreCase(ticket.getPriority())) {
            return "HIGH";
        }
        return MEDIUM;
    }

    private static String offlineSeverity(DeviceInfo device) {
        Instant updated = device.getUpdatedAt();
        return updated != null && updated.isBefore(Instant.now().minus(2, ChronoUnit.HOURS))
                ? "HIGH" : MEDIUM;
    }

    private static int severityRank(String severity) {
        return switch (String.valueOf(severity).toUpperCase()) {
            case CRITICAL -> 0;
            case "HIGH" -> 1;
            case MEDIUM -> 2;
            default -> 3;
        };
    }

    private static String formatDisputeReasonText(String reason) {
        if (reason == null || reason.isBlank()) {
            return "识别结果需人工审核";
        }
        String trimmed = reason.trim();
        if (trimmed.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF)) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("recognition needs manual review") || lower.contains("manual review")) {
            return "识别结果需人工审核";
        }
        if (lower.contains("no items") || lower.contains("not recognized")) {
            return "未识别到商品，需人工审核";
        }
        return trimmed;
    }

    private static String replenishStatusLabel(String status) {
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

    private static String payChannelLabel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "未知";
        }
        return switch (channel.toUpperCase()) {
            case "WECHAT" -> "微信";
            case "ALIPAY" -> "支付宝";
            case "BALANCE" -> "余额";
            case "MOCK" -> "其他";
            case "UNKNOWN" -> "未知";
            default -> channel;
        };
    }

    private static String splitStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status.toUpperCase()) {
            case STATUS_PENDING -> "待分账";
            case "SETTLED" -> "已分账";
            case "VOIDED", "REVERSED" -> "已冲正";
            case CabinetConstants.ORDER_STATUS_FAILED, WECHAT_FAILED -> "分账失败";
            case LEDGER_ONLY -> "仅记账";
            default -> status;
        };
    }
}
