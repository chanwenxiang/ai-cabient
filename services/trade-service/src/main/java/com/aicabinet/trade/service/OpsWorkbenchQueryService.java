package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdminStatsDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.common.dto.OpsDashboardBundleDto;
import com.aicabinet.common.dto.OpsWorkbenchDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ReplenishmentTask;
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
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
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
    private static final List<String> SPLIT_EXCEPTION_STATUSES = List.of(
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
    private final DeviceSlotService deviceSlotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final PaymentReconciliationMapper reconciliationRepository;
    private final WarehouseInTransitMapper inTransitRepository;
    private final OpsExceptionMapper exceptionRepository;
    private final OpsSessionOrderQueryService sessionOrderQueryService;

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
                                    DeviceSlotService deviceSlotService,
                                    ReplenishmentTaskMapper replenishmentTaskRepository,
                                    PaymentReconciliationMapper reconciliationRepository,
                                    WarehouseInTransitMapper inTransitRepository,
                                    OpsExceptionMapper exceptionRepository,
                                    OpsSessionOrderQueryService sessionOrderQueryService) {
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
        this.deviceSlotService = deviceSlotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.inTransitRepository = inTransitRepository;
        this.exceptionRepository = exceptionRepository;
        this.sessionOrderQueryService = sessionOrderQueryService;
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
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
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
                sessionRepository.countByDeviceIdInAndState(scopedDevices, SessionState.WAITING_UPLOAD),
                slaRealtime.doorSuccessRate24h(),
                disputeRate,
                recognitionAutoRate,
                inventoryRepository.countLowStock(),
                splitRepository.countByStatusIn(PENDING_SPLIT_STATUSES),
                inventoryLotService.countNearExpiryLots(),
                inventoryLotService.countExpiredLotsWithStock(),
                inventoryLotService.countOpenPullOffTasks(),
                deviceSlotService.countDiscrepancies(operatorId)
        );
    }

    @Transactional(readOnly = true)
    public OpsWorkbenchDto workbench(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DASHBOARD_VIEW);
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        collectDisputeActionItems(scopedDevices, items);
        collectUploadStuckItems(scopedDevices, items);
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

    private void collectDisputeActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(scopedDevices, sessionDeviceId(d.getSessionId())))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE",
                        disputeSeverity(d),
                        "待审核争议",
                        formatDisputeReasonText(d.getReason()),
                        sessionDeviceId(d.getSessionId()),
                        d.getSessionId(),
                        d.getTicketId(),
                        null,
                        null,
                        d.getCreatedAt(),
                        d.getSlaDueAt()
                )));
    }

    private void collectUploadStuckItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        sessionRepository.findTop10ByStateOrderByUpdatedAtAsc(SessionState.WAITING_UPLOAD).stream()
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .forEach(s -> items.add(new OpsActionItemDto(
                        "UPLOAD_STUCK",
                        uploadSeverity(s),
                        "视频待上传",
                        "上传状态：" + uploadStatusLabel(s.getUploadStatus()),
                        s.getDeviceId(),
                        s.getSessionId(),
                        null,
                        null,
                        null,
                        s.getCreatedAt(),
                        s.getUpdatedAt().plus(30, ChronoUnit.MINUTES)
                )));
    }

    private void collectOfflineDeviceItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        deviceRepository.findByOnlineStatusNot(CabinetConstants.DEVICE_ONLINE, WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(scopedDevices, d.getDeviceId()))
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

    private void collectLowStockItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        inventoryRepository.findLowStockLimit(WORKBENCH_ITEM_CAP).stream()
                .filter(i -> inDeviceScope(scopedDevices, i.getId().getDeviceId()))
                .forEach(i -> items.add(new OpsActionItemDto(
                        "LOW_STOCK",
                        MEDIUM,
                        "库存偏低",
                        "当前库存 " + i.getQuantity() + "，预警阈值 " + i.getLowThreshold(),
                        i.getId().getDeviceId(),
                        null,
                        null,
                        i.getId().getSkuId(),
                        null,
                        i.getUpdatedAt(),
                        null
                )));
    }

    private void collectReplenishmentActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), WORKBENCH_ITEM_CAP).stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
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
                "状态 " + s.getState() + "，上传 " + uploadStatusLabel(s.getUploadStatus()),
                s.getDeviceId(),
                s.getSessionId(),
                null,
                null,
                null,
                s.getCreatedAt(),
                s.getUpdatedAt().plus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES)
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

    private void collectSplitExceptionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        SPLIT_EXCEPTION_STATUSES.forEach(status ->
                splitRepository.findTop20ByStatusOrderByCreatedAtAsc(status).stream()
                        .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
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

    private void collectInTransitOverdueItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {
        Instant transitCutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        // 先按行拉取再按出库单聚合，避免同一出库单拆成十几条「紧急」刷屏
        List<WarehouseInTransit> overdueLines = inTransitRepository
                .findByStatusAndCreatedAtBefore(IN_TRANSIT, transitCutoff, 500)
                .stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .toList();
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
                    "补货签收超时",
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
                    "补货签收超时",
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

    private long countInTransitOverdue(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        // 与告警明细一致：按出库单计数（无出库单号时按在途行）
        return inTransitRepository.findByStatusAndCreatedAtBefore(IN_TRANSIT, cutoff, 500).stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .map(t -> t.getOutboundId() != null ? t.getOutboundId() : t.getTransitId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }

    private OpsWorkbenchDto buildWorkbenchDto(
            Long operatorId, Set<String> scopedDevices, List<OpsActionItemDto> items) {
        List<DeviceInfo> scopedDeviceList = merchantScopeService.allowedDevices(operatorId);
        long devicesSalesLocked = scopedDeviceList.stream().filter(DeviceInfo::salesLockedEnabled).count();
        return new OpsWorkbenchDto(
                countOpenDisputes(scopedDevices),
                disputeSlaService.countOverdue(),
                countOfflineDevices(scopedDevices),
                countWaitingUploads(scopedDevices),
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
        return new AdminStatsDto(
                deviceRepository.count(),
                deviceRepository.countByOnlineStatus(CabinetConstants.DEVICE_ONLINE),
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
                sessionRepository.countByState(SessionState.WAITING_UPLOAD),
                slaRealtime.doorSuccessRate24h(),
                disputeRate,
                recognitionAutoRate,
                inventoryRepository.countLowStock(),
                splitRepository.countByStatusIn(PENDING_SPLIT_STATUSES),
                inventoryLotService.countNearExpiryLots(),
                inventoryLotService.countExpiredLotsWithStock(),
                inventoryLotService.countOpenPullOffTasks(),
                deviceSlotService.countDiscrepancies(operatorId)
        );
    }

    private static AdminStatsDto emptyStats() {
        return new AdminStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0, 0.0, 1.0, 0, 0, 0, 0, 0, 0);
    }

    private long countOccupiedDevices(Set<String> scopedDevices) {
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return 0;
        }
        Set<String> occupied = new HashSet<>();
        long sessionDevices = scopedDevices == null
                ? sessionRepository.countDistinctDeviceIdByStateIn(ACTIVE_STATES)
                : sessionRepository.countDistinctDeviceIdByDeviceIdInAndStateIn(scopedDevices, ACTIVE_STATES);
        // 补货 IN_PROGRESS 占柜：与会话设备并集（任务量通常远小于会话全表）
        for (ReplenishmentTask t : replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(STATUS_IN_PROGRESS), 500)) {
            if (inDeviceScope(scopedDevices, t.getDeviceId())) {
                occupied.add(t.getDeviceId());
            }
        }
        // 无法无会话设备列表精确并集时：取「会话占柜」与「补货占柜」的上界近似
        // （补货设备通常也有补货会话；若仅有任务无会话，用 max 会略低估，可接受）
        return Math.max(sessionDevices, occupied.size());
    }

    private long countOpenDisputes(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return disputeRepository.countByStatus("OPEN");
        }
        // 作用域内无法无会话表直算时，用有限样本过滤计数（与待办列表一致上限）
        return disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", 500).stream()
                .filter(d -> inDeviceScope(scopedDevices, sessionDeviceId(d.getSessionId())))
                .count();
    }

    private long countOfflineDevices(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return deviceRepository.countByOnlineStatusNot(CabinetConstants.DEVICE_ONLINE);
        }
        return deviceRepository.countByDeviceIdInAndOnlineStatusNot(scopedDevices, CabinetConstants.DEVICE_ONLINE);
    }

    private long countWaitingUploads(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return sessionRepository.countByState(SessionState.WAITING_UPLOAD);
        }
        return sessionRepository.countByDeviceIdInAndState(scopedDevices, SessionState.WAITING_UPLOAD);
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
        return sessionRepository.findByStateInAndUpdatedAtBefore(ACTIVE_STATES, cutoff, WORKBENCH_ITEM_CAP).stream()
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .toList();
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

    private String sessionDeviceId(String sessionId) {
        return sessionRepository.findById(sessionId).map(ShoppingSession::getDeviceId).orElse(null);
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

    private static boolean inDeviceScope(Set<String> scopedDevices, String deviceId) {
        return scopedDevices == null || (deviceId != null && scopedDevices.contains(deviceId));
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

    private static String uploadSeverity(ShoppingSession session) {
        return session.getUpdatedAt().isBefore(Instant.now().minus(30, ChronoUnit.MINUTES))
                ? "HIGH" : MEDIUM;
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

    private static String uploadStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "未上传";
        }
        return switch (status.toUpperCase()) {
            case "LOCAL_QUEUED" -> "本地排队";
            case "UPLOADING" -> "上传中";
            case "UPLOADED" -> "已上传";
            case CabinetConstants.ORDER_STATUS_FAILED -> "上传失败";
            default -> status;
        };
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
