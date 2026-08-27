package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.AliyunCategoryMapping;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminDashboardService {
    private static final String PERM_OPS_DASHBOARD_VIEW = "ops:dashboard:view";
    private static final String PERM_OPS_ANALYTICS_VIEW = "ops:analytics:view";
    private static final String PERM_OPS_DEVICE_LIST = "ops:device:list";
    private static final String WECHAT_FAILED = "WECHAT_FAILED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String LEDGER_ONLY = "LEDGER_ONLY";
    private static final String IN_TRANSIT = "IN_TRANSIT";
    private static final String CREATEDAT = "createdAt";
    private static final String CRITICAL = "CRITICAL";
    private static final String DEPLOYED = "DEPLOYED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String MEDIUM = "MEDIUM";


    private static final int EXPORT_LIMIT = 5000;
    /** 工作台「待支付」与订单页 overdue=1 对齐：超过该分钟仍 PENDING 计入。 */
    public static final int UNPAID_OPS_OVERDUE_MINUTES = 30;
    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private static final List<SessionState> CLOSED_STATES = List.of(
            SessionState.COMPLETED, SessionState.DISPUTED
    );
    private static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "ACCRUED", LEDGER_ONLY, CabinetConstants.ORDER_STATUS_FAILED, WECHAT_FAILED
    );
    private static final List<String> SPLIT_EXCEPTION_STATUSES = List.of(CabinetConstants.ORDER_STATUS_FAILED, WECHAT_FAILED, LEDGER_ONLY);
    private static final long STALE_SESSION_MINUTES = 30;
    private static final long IN_TRANSIT_OVERDUE_HOURS = 24;
    /** 工作台待办每类最多展示条数，避免全表加载。 */
    private static final int WORKBENCH_ITEM_CAP = 20;

    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DisputeTicketMapper disputeRepository;
    private final SettlementService settlementService;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final AdminAuditService auditService;
    private final AdminAuditLogMapper auditLogRepository;
    private final PermissionService permissionService;
    private final PaymentService paymentService;
    private final RechargeOrderMapper rechargeOrderRepository;
    private final SlaMetricsService slaMetricsService;
    private final MinioVideoService minioVideoService;
    private final MerchantMapper merchantRepository;
    private final MerchantScopeService merchantScopeService;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final DisputeSlaService disputeSlaService;
    private final InventoryLotService inventoryLotService;
    private final DeviceSlotService deviceSlotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final PaymentReconciliationMapper reconciliationRepository;
    private final WarehouseInTransitMapper inTransitRepository;
    private final BalanceLedgerService balanceLedgerService;
    private final RefundPolicyService refundPolicyService;
    private final OpsExceptionMapper exceptionRepository;
    private final FileAttachmentService fileAttachmentService;
    private final MemberMapper memberRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final DistributedLockService distributedLockService;
    private final AliyunCategoryMappingMapper aliyunCategoryMappingRepository;
    private final AdminDashboardService self;

    public AdminDashboardService(DeviceInfoMapper deviceRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 CabinetOrderMapper orderRepository,
                                 CabinetOrderLineMapper orderLineRepository,
                                 DisputeTicketMapper disputeRepository,
                                 SettlementService settlementService,
                                 UserInfoMapper userInfoRepository,
                                 UserAccountMapper userAccountRepository,
                                 SkuCatalogMapper skuCatalogRepository,
                                 AdminAuditService auditService,
                                 AdminAuditLogMapper auditLogRepository,
                                 PermissionService permissionService,
                                 PaymentService paymentService,
                                 RechargeOrderMapper rechargeOrderRepository,
                                 SlaMetricsService slaMetricsService,
                                 MinioVideoService minioVideoService,
                                 MerchantMapper merchantRepository,
                                 MerchantScopeService merchantScopeService,
                                 DeviceSkuInventoryMapper inventoryRepository,
                                 OrderRevenueSplitMapper splitRepository,
                                 DisputeSlaService disputeSlaService,
                                 InventoryLotService inventoryLotService,
                                 DeviceSlotService deviceSlotService,
                                 ReplenishmentTaskMapper replenishmentTaskRepository,
                                 PaymentReconciliationMapper reconciliationRepository,
                                 WarehouseInTransitMapper inTransitRepository,
                                 BalanceLedgerService balanceLedgerService,
                                 RefundPolicyService refundPolicyService,
                                 OpsExceptionMapper exceptionRepository,
                                 FileAttachmentService fileAttachmentService,
                                 MemberMapper memberRepository,
                                 UserBlacklistMapper blacklistRepository,
                                 DistributedLockService distributedLockService,
                                 AliyunCategoryMappingMapper aliyunCategoryMappingRepository,
                                 @Lazy AdminDashboardService self) {
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.disputeRepository = disputeRepository;
        this.settlementService = settlementService;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.permissionService = permissionService;
        this.paymentService = paymentService;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.slaMetricsService = slaMetricsService;
        this.minioVideoService = minioVideoService;
        this.merchantRepository = merchantRepository;
        this.merchantScopeService = merchantScopeService;
        this.inventoryRepository = inventoryRepository;
        this.splitRepository = splitRepository;
        this.disputeSlaService = disputeSlaService;
        this.inventoryLotService = inventoryLotService;
        this.deviceSlotService = deviceSlotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.inTransitRepository = inTransitRepository;
        this.balanceLedgerService = balanceLedgerService;
        this.refundPolicyService = refundPolicyService;
        this.exceptionRepository = exceptionRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.memberRepository = memberRepository;
        this.blacklistRepository = blacklistRepository;
        this.distributedLockService = distributedLockService;
        this.aliyunCategoryMappingRepository = aliyunCategoryMappingRepository;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<DeviceRefDto> listDeviceRefs(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DEVICE_LIST, "ops:device:ref");
        return merchantScopeService.allowedDevices(operatorId).stream()
                .sorted(java.util.Comparator.comparing(DeviceInfo::getDeviceId))
                .limit(500)
                .map(d -> new DeviceRefDto(d.getDeviceId(), d.getDeviceName(), d.getOnlineStatus(), d.getMerchantId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OpsDashboardBundleDto dashboardBundle(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DASHBOARD_VIEW);
        AdminStatsDto stats = stats(operatorId);
        OpsWorkbenchDto wb = self.workbench(operatorId);
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

        List<DisputeTicket> openDisputes = disputeRepository
                .findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(scopedDevices, sessionDeviceId(d.getSessionId())))
                .toList();
        openDisputes.forEach(d -> items.add(new OpsActionItemDto(
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

        List<ShoppingSession> waitingUploads = sessionRepository
                .findTop10ByStateOrderByUpdatedAtAsc(SessionState.WAITING_UPLOAD).stream()
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .toList();
        waitingUploads.forEach(s -> items.add(new OpsActionItemDto(
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

        List<DeviceInfo> offlineDevices = deviceRepository
                .findByOnlineStatusNot(CabinetConstants.DEVICE_ONLINE, WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(scopedDevices, d.getDeviceId()))
                .toList();
        offlineDevices.forEach(d -> items.add(new OpsActionItemDto(
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

        SPLIT_EXCEPTION_STATUSES.forEach(status ->
                splitRepository.findTop20ByStatusOrderByCreatedAtAsc(status).stream()
                        .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                        .limit(5)
                        .forEach(s -> items.add(new OpsActionItemDto(
                                "SPLIT_EXCEPTION",
                                CabinetConstants.ORDER_STATUS_FAILED.equalsIgnoreCase(s.getStatus()) || WECHAT_FAILED.equalsIgnoreCase(s.getStatus())
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

        Instant transitCutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        inTransitRepository.findByStatusAndCreatedAtBefore(IN_TRANSIT, transitCutoff, WORKBENCH_ITEM_CAP).stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .forEach(t -> items.add(new OpsActionItemDto(
                        "IN_TRANSIT_OVERDUE",
                        "HIGH",
                        "补货签收超时",
                        "出库单 " + t.getOutboundId() + " · 商品 " + t.getSkuId()
                                + " · 批次 " + t.getBatchNo() + " · 数量 " + t.getQuantity(),
                        t.getDeviceId(),
                        null,
                        null,
                        t.getSkuId(),
                        null,
                        t.getCreatedAt(),
                        t.getCreatedAt().plus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS)
                )));

        items.sort(java.util.Comparator
                .comparingInt((OpsActionItemDto item) -> severityRank(item.severity()))
                .thenComparing(item -> item.dueAt() != null ? item.dueAt() : Instant.MAX));

        long staleSessions = countStaleSessions(scopedDevices);
        long splitExceptions = countSplitExceptions(scopedDevices);
        long inTransitOverdue = countInTransitOverdue(scopedDevices);
        List<DeviceInfo> scopedDeviceList = merchantScopeService.allowedDevices(operatorId);
        long devicesSalesLocked = scopedDeviceList.stream().filter(DeviceInfo::salesLockedEnabled).count();
        long devicesOnSale = scopedDeviceList.size() - devicesSalesLocked;
        long pendingUnpaidOrders = countOverdueUnpaidOrders(operatorId);
        return new OpsWorkbenchDto(
                countOpenDisputes(scopedDevices),
                disputeSlaService.countOverdue(),
                countOfflineDevices(scopedDevices),
                countWaitingUploads(scopedDevices),
                countLowStock(scopedDevices),
                countPendingReplenishments(scopedDevices),
                staleSessions,
                reconciliationRepository.countByStatus("MISMATCH"),
                splitExceptions,
                inTransitOverdue,
                items.stream().limit(100).toList(),
                devicesOnSale,
                devicesSalesLocked,
                pendingUnpaidOrders
        );
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

    private long countInTransitOverdue(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        if (scopedDevices == null) {
            return inTransitRepository.countByStatusAndCreatedAtBefore(IN_TRANSIT, cutoff);
        }
        return inTransitRepository.countByStatusAndCreatedAtBeforeAndDeviceIdIn(
                IN_TRANSIT, cutoff, scopedDevices);
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

    private Set<String> replenishingDeviceIds() {
        return replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of(STATUS_IN_PROGRESS), 500).stream()
                .map(ReplenishmentTask::getDeviceId)
                .collect(Collectors.toSet());
    }

    public List<AdminDeviceDto> listDevices(Long operatorId) {
        return listDevicesPaged(operatorId, 0, 5000, null, null).items();
    }

    @Transactional(readOnly = true)
    public AdminDeviceDto getDevice(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo d = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        return toDeviceDto(d, findSessionForDeviceList(deviceId), replenishingDeviceIds().contains(deviceId));
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online) {
        return listDevicesPaged(operatorId, page, size, q, online, null);
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online, Boolean salesLocked) {
        return listDevicesPaged(operatorId, page, size, q, online, salesLocked, null, null, null);
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online, Boolean salesLocked,
                                                         String lifecycleStatus, String coopMode, String routeCode) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
        Set<String> replenishing = replenishingDeviceIds();
        Map<String, ShoppingSession> sessionByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(
                        ShoppingSession::getDeviceId,
                        s -> s,
                        AdminDashboardService::preferSessionForDeviceList
                ));

        String kw = q == null ? "" : q.trim().toLowerCase();
        String onlineFilter = online == null ? "" : online.trim().toUpperCase();
        String lifeFilter = lifecycleStatus == null ? "" : lifecycleStatus.trim().toUpperCase();
        String coopFilter = coopMode == null ? "" : coopMode.trim().toUpperCase();
        String routeFilter = routeCode == null ? "" : routeCode.trim().toLowerCase();

        List<AdminDeviceDto> filtered = devices.stream()
                .map(d -> toDeviceDto(d, sessionByDevice.get(d.getDeviceId()), replenishing.contains(d.getDeviceId())))
                .filter(d -> {
                    if (!onlineFilter.isEmpty() && !onlineFilter.equalsIgnoreCase(String.valueOf(d.onlineStatus()))) {
                        return false;
                    }
                    if (salesLocked != null && d.salesLocked() != salesLocked) {
                        return false;
                    }
                    if (!lifeFilter.isEmpty()
                            && !lifeFilter.equalsIgnoreCase(String.valueOf(d.lifecycleStatus() == null ? DEPLOYED : d.lifecycleStatus()))) {
                        return false;
                    }
                    if (!coopFilter.isEmpty()
                            && !coopFilter.equalsIgnoreCase(String.valueOf(d.coopMode() == null ? "" : d.coopMode()))) {
                        return false;
                    }
                    if (!routeFilter.isEmpty()
                            && !String.valueOf(d.routeCode() == null ? "" : d.routeCode()).toLowerCase().contains(routeFilter)) {
                        return false;
                    }
                    if (kw.isEmpty()) {
                        return true;
                    }
                    return String.valueOf(d.deviceId()).toLowerCase().contains(kw)
                            || String.valueOf(d.deviceName() == null ? "" : d.deviceName()).toLowerCase().contains(kw)
                            || String.valueOf(d.merchantId() == null ? "" : d.merchantId()).toLowerCase().contains(kw)
                            || String.valueOf(d.merchantName() == null ? "" : d.merchantName()).toLowerCase().contains(kw)
                            || String.valueOf(d.imei() == null ? "" : d.imei()).toLowerCase().contains(kw)
                            || String.valueOf(d.assetOwner() == null ? "" : d.assetOwner()).toLowerCase().contains(kw)
                            || String.valueOf(d.opsTags() == null ? "" : d.opsTags()).toLowerCase().contains(kw)
                            || String.valueOf(d.routeCode() == null ? "" : d.routeCode()).toLowerCase().contains(kw);
                })
                .toList();

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResult<>(filtered.subList(from, to), safePage, safeSize, filtered.size());
    }

    private static ShoppingSession preferSessionForDeviceList(ShoppingSession a, ShoppingSession b) {
        boolean aActive = ACTIVE_STATES.contains(a.getState());
        boolean bActive = ACTIVE_STATES.contains(b.getState());
        if (aActive != bActive) {
            return aActive ? a : b;
        }
        Instant au = sessionTouchTime(a);
        Instant bu = sessionTouchTime(b);
        return au.isAfter(bu) ? a : b;
    }

    private static Instant sessionTouchTime(ShoppingSession s) {
        if (s.getUpdatedAt() != null) {
            return s.getUpdatedAt();
        }
        return s.getCreatedAt() != null ? s.getCreatedAt() : Instant.EPOCH;
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, int page, int size,
                                                      String deviceId, SessionState state) {
        return listSessions(operatorId, page, size, deviceId, state, null, null, null, null, null);
    }

    public PageResult<AdminSessionDto> listSessions(
            Long operatorId,
            int page,
            int size,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword) {
        return listSessions(operatorId, page, size, deviceId, state, sessionId, userId, from, to, keyword,
                null, false, 30);
    }

    public PageResult<AdminSessionDto> listSessions(
            Long operatorId,
            int page,
            int size,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword,
            String uploadStatus,
            boolean stuckOnly,
            int stuckMinutes) {
        permissionService.requireAnyPermission(operatorId, "ops:session:list", "ops:session:upload");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Instant updatedBefore = stuckOnly
                ? Instant.now().minus(Math.max(stuckMinutes, 1), ChronoUnit.MINUTES)
                : null;
        Page<ShoppingSession> result = querySessions(
                operatorId, deviceId, state, sessionId, userId, from, to, keyword,
                blankToNull(uploadStatus), updatedBefore, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSessionDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, int page, int size, String deviceId) {
        return self.listOrders(operatorId, page, size, deviceId, null, false,
                null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status) {
        return self.listOrders(operatorId, page, size, deviceId, status, false,
                null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status, boolean overdueOnly) {
        return self.listOrders(operatorId, page, size, deviceId, status, overdueOnly,
                null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId,
            int page,
            int size,
            String deviceId,
            String status,
            boolean overdueOnly,
            String orderId,
            Long userId,
            String sessionId,
            String payTradeNo,
            String payChannel,
            Instant from,
            Instant to,
            String keyword) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Instant createdBefore = null;
        if (overdueOnly) {
            createdBefore = Instant.now().minus(UNPAID_OPS_OVERDUE_MINUTES, ChronoUnit.MINUTES);
            if (status == null || status.isBlank()) {
                status = STATUS_PENDING;
            }
        }
        Page<CabinetOrder> result = queryOrders(
                operatorId, deviceId, status, createdBefore, from, to,
                orderId, userId, sessionId, payTradeNo, payChannel, keyword, pageable);
        List<String> orderIds = result.getContent().stream().map(CabinetOrder::getOrderId).toList();
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(orderIds);
        Map<String, List<CabinetOrderLine>> linesByOrder = loadOrderLinesByOrderIds(orderIds);
        return new PageResult<>(
                result.getContent().stream()
                        .map(o -> toOrderSummary(
                                o,
                                qtyByOrder.getOrDefault(o.getOrderId(), 0),
                                linesByOrder.getOrDefault(o.getOrderId(), List.of())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    private long countOverdueUnpaidOrders(Long operatorId) {
        Instant cutoff = Instant.now().minus(UNPAID_OPS_OVERDUE_MINUTES, ChronoUnit.MINUTES);
        return queryOrders(operatorId, null, STATUS_PENDING, cutoff, PageRequest.of(0, 1)).getTotalElements();
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long operatorId, String orderId) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, order.getDeviceId());
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional
    public AdminSessionDto cancelSession(Long operatorId, String sessionId) {
        permissionService.requirePermission(operatorId, "ops:session:cancel");
        ShoppingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        if (EnumSet.of(SessionState.COMPLETED, SessionState.CANCELLED, SessionState.FAILED).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_FINISHED);
        }
        // 识别/结算中请走异常中心，避免截断库存与录像链路
        if (EnumSet.of(SessionState.WAITING_UPLOAD, SessionState.RECOGNIZING, SessionState.SETTLING, SessionState.DISPUTED)
                .contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "识别/结算中的会话请到异常中心处理，不可直接取消");
        }
        SessionState previous = session.getState();
        session.setState(SessionState.CANCELLED);
        sessionRepository.save(session);
        auditService.record(operatorId, "SESSION_CANCEL", "SESSION", sessionId,
                "device=" + session.getDeviceId() + " previous=" + previous);
        return toSessionDto(session);
    }

    @Transactional(readOnly = true)
    public void streamSessionVideo(Long operatorId, String sessionId,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   HttpServletResponse response) {
        permissionService.requireAnyPermission(operatorId, "ops:session:list", "ops:session:upload", "ops:dispute");
        ShoppingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        String videoUri = session.getVideoUri();
        if (videoUri == null || videoUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该会话没有关联视频");
        }
        minioVideoService.streamTo(videoUri, request, response);
    }

    public List<AdminDeviceReportDto> deviceReports(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:report:device");
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(ShoppingSession::getDeviceId, s -> s, (a, b) -> a));

        return merchantScopeService.allowedDevices(operatorId).stream()
                .map(d -> {
                    String id = d.getDeviceId();
                    return new AdminDeviceReportDto(
                            id,
                            d.getDeviceName(),
                            d.getOnlineStatus(),
                            orderRepository.countByDeviceId(id),
                            orderRepository.sumAmountByDeviceId(id),
                            orderRepository.countByDeviceIdAndCreatedAtAfter(id, todayStart),
                            orderRepository.sumAmountByDeviceIdSince(id, todayStart),
                            sessionRepository.countByDeviceId(id),
                            activeByDevice.containsKey(id) ? 1 : 0
                    );
                })
                .toList();
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(Long operatorId, int page, int size, boolean logIdAsc) {
        return listAuditLogs(operatorId, page, size, logIdAsc, null, null, false);
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(
            Long operatorId,
            int page,
            int size,
            boolean logIdAsc,
            String action,
            String targetType,
            boolean mineOnly) {
        permissionService.requirePermission(operatorId, "ops:audit:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Long operatorFilter = mineOnly ? operatorId : null;
        Page<com.aicabinet.trade.domain.AdminAuditLog> result = auditLogRepository.searchPage(
                operatorFilter, blankToNull(action), blankToNull(targetType), logIdAsc, p, s);
        return toAuditPage(result);
    }

    public List<AdminAuditLogDto> listRecentAuditLogs(Long operatorId, int size, boolean mineOnly) {
        permissionService.requireAnyPermission(operatorId, "ops:audit:recent", "ops:audit:list");
        int limit = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(0, limit);
        Page<com.aicabinet.trade.domain.AdminAuditLog> result = mineOnly
                ? auditLogRepository.findByOperatorIdOrderByCreatedAtDesc(operatorId, pageable)
                : auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return enrichAuditLogs(result.getContent());
    }

    private PageResult<AdminAuditLogDto> toAuditPage(Page<com.aicabinet.trade.domain.AdminAuditLog> result) {
        return new PageResult<>(
                enrichAuditLogs(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    private List<AdminAuditLogDto> enrichAuditLogs(List<com.aicabinet.trade.domain.AdminAuditLog> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }
        List<Long> operatorIds = logs.stream()
                .map(com.aicabinet.trade.domain.AdminAuditLog::getOperatorId)
                .distinct()
                .toList();
        Map<Long, UserInfo> users = userInfoRepository.findByUserIdIn(operatorIds).stream()
                .collect(Collectors.toMap(UserInfo::getUserId, u -> u));
        return logs.stream()
                .map(log -> toAuditDto(log, users.get(log.getOperatorId())))
                .toList();
    }

    public PageResult<AdminUserDto> listUsers(Long operatorId, int page, int size, String phone,
                                              String name, String role, Boolean verified) {
        permissionService.requirePermission(operatorId, "ops:user:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Long minUserId = null;
        Long maxUserId = null;
        if (role != null && !role.isBlank()) {
            if ("OPERATOR".equalsIgnoreCase(role.trim())) {
                minUserId = CabinetConstants.OPERATOR_USER_ID_START;
            } else if ("CONSUMER".equalsIgnoreCase(role.trim())) {
                maxUserId = CabinetConstants.OPERATOR_USER_ID_START - 1;
            }
        }
        Page<UserInfo> result = userInfoRepository.searchForAdmin(
                trimToNull(phone),
                trimToNull(name),
                verified,
                minUserId,
                maxUserId,
                pageable);
        List<Long> userIds = result.getContent().stream().map(UserInfo::getUserId).toList();
        Map<Long, Member> memberByUser = memberRepository.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(Member::getUserId, m -> m, (a, b) -> a));
        Set<Long> blacklistedUsers = blacklistRepository.findActiveUserIds(userIds);
        Map<Long, Integer> balanceByUser = userAccountRepository.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getUserId, UserAccount::getBalanceCents, (a, b) -> a));
        return new PageResult<>(
                result.getContent().stream()
                        .map(u -> toUserDto(u,
                                balanceByUser.getOrDefault(u.getUserId(), 0),
                                memberByUser.get(u.getUserId()),
                                blacklistedUsers.contains(u.getUserId())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        return self.listSkus(operatorId, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkus(Long operatorId, String q, String status, String category) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        return self.listSkusPage(operatorId, q, status, category, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<SkuCatalogDto> listSkusPage(
            Long operatorId, String q, String status, String category, int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var result = skuCatalogRepository.search(q, status, category, p, s);
        List<SkuCatalogDto> items = result.getRecords().stream().map(SkuCatalog::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public SkuCatalogDto createSku(Long operatorId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        long code = skuCatalogRepository.nextSkuCode();
        String skuId = request.skuId() != null && !request.skuId().isBlank()
                ? request.skuId().trim()
                : "SKU-" + code;
        if (skuCatalogRepository.existsById(skuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_EXISTS);
        }
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, null);
        assertSkuNameUnique(request.skuName(), null);
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuCode(code);
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        if (sku.getCreatedAt() == null) {
            sku.setCreatedAt(Instant.now());
        }
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_CREATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        String oldImageUrl = sku.getImageUrl();
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, skuId);
        assertSkuNameUnique(request.skuName(), skuId);
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        String newImageUrl = trimToNull(request.imageUrl());
        if (oldImageUrl != null && !oldImageUrl.equals(newImageUrl)) {
            // 主图被替换/清空时释放旧图（无引用则删除对象），避免孤儿文件堆积
            fileAttachmentService.releaseSkuImageIfUnused(oldImageUrl, operatorId);
        }
        auditService.record(operatorId, "SKU_UPDATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
    }

    private void assertBarcodeUnique(String barcode, String excludeSkuId) {
        if (skuCatalogRepository.existsByBarcode(barcode, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_BARCODE_EXISTS);
        }
    }

    private void assertSkuNameUnique(String skuName, String excludeSkuId) {
        if (skuCatalogRepository.existsBySkuName(skuName, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_NAME_EXISTS);
        }
    }

    private static void applySkuRequest(SkuCatalog sku, UpsertSkuRequest request) {
        sku.setSkuName(request.skuName().trim());
        sku.setPriceCents(request.priceCents());
        sku.setWeightGrams(request.weightGrams());
        sku.setVisionEnabled(request.visionEnabled());
        sku.setImageUrl(trimToNull(request.imageUrl()));
        sku.setDescription(trimToNull(request.description()));
        sku.setCategory(trimToNull(request.category()));
        sku.setBarcode(trimToNull(request.barcode()));
        sku.setBrand(trimToNull(request.brand()));
        sku.setSpec(trimToNull(request.spec()));
        sku.setUnit(request.unit() != null && !request.unit().isBlank() ? request.unit().trim() : "件");
        sku.setStatus(request.status());
        sku.setShelfLifeDays(request.shelfLifeDays());
        sku.setNearExpiryDays(request.nearExpiryDays());
        sku.setBlockSaleDaysBeforeExpiry(request.blockSaleDaysBeforeExpiry());
        sku.setStorageType(request.storageType());
        sku.setPurchaseCostCents(request.purchaseCostCents());
        sku.setNearExpiryPriceCents(request.nearExpiryPriceCents());
        if (request.minChargeConfidence() != null) {
            sku.setMinChargeConfidence(request.minChargeConfidence());
        }
        if (request.yoloClassName() != null && !request.yoloClassName().isBlank()) {
            sku.setYoloClassName(request.yoloClassName().trim());
        }
        if (request.visionEnrollmentStatus() != null && !request.visionEnrollmentStatus().isBlank()) {
            sku.setVisionEnrollmentStatus(request.visionEnrollmentStatus().trim().toUpperCase());
        }
        if (request.detectionMinConfidence() != null) {
            sku.setDetectionMinConfidence(request.detectionMinConfidence());
        }
        if (request.referenceImageUrlsJson() != null) {
            sku.setReferenceImageUrlsJson(trimToNull(request.referenceImageUrlsJson()));
        }
    }

    private void touchSkuUpdater(SkuCatalog sku, Long operatorId) {
        if (operatorId == null || operatorId <= 0L) {
            sku.setUpdatedByUserId(null);
            sku.setUpdatedByName("系统");
            return;
        }
        sku.setUpdatedByUserId(operatorId);
        UserInfo user = userInfoRepository.findById(operatorId).orElse(null);
        String name = user != null ? user.getName() : null;
        String phone = user != null ? user.getPhoneNumber() : null;
        if (name == null || name.isBlank()) {
            name = phone != null && !phone.isBlank() ? phone : ("账号 " + operatorId);
        }
        sku.setUpdatedByName(name);
    }

    private void syncSkuCategoryId(SkuCatalog sku) {
        String category = sku.getCategory();
        if (category == null || category.isBlank()) {
            sku.setCategoryId(null);
            return;
        }
        AliyunCategoryMapping mapping = aliyunCategoryMappingRepository.selectOne(
                Wrappers.<AliyunCategoryMapping>lambdaQuery()
                        .eq(AliyunCategoryMapping::getCategoryName, category.trim())
                        .last("LIMIT 1"));
        sku.setCategoryId(mapping != null ? mapping.getCategoryId() : null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public AdminDeviceDto createDevice(Long operatorId, UpsertDeviceRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        String deviceId = request.deviceId().trim();
        if (deviceRepository.existsById(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_EXISTS);
        }
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(request.deviceName() != null ? request.deviceName().trim() : deviceId);
        device.setDeviceType(request.deviceType() != null && !request.deviceType().isBlank()
                ? request.deviceType().trim() : "AI_CABINET_V1");
        device.setOnlineStatus("OFFLINE");
        if (request.merchantId() != null && !request.merchantId().isBlank()) {
            String merchantId = request.merchantId().trim();
            requireMerchant(merchantId);
            merchantScopeService.requireMerchantAccess(operatorId, merchantId);
            device.setMerchantId(merchantId);
            device.setLifecycleStatus(DEPLOYED);
            device.setDeployedAt(Instant.now());
        } else {
            device.setLifecycleStatus("IDLE");
        }
        deviceRepository.save(device);
        deviceSlotService.ensureDefaultSlots(deviceId, device.getDeviceType());
        auditService.record(operatorId, "DEVICE_CREATE", "DEVICE", deviceId, device.getDeviceName());
        return toDeviceDto(device, null, false);
    }

    @Transactional
    public AdminDeviceDto updateDevice(Long operatorId, String deviceId, UpdateDeviceRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            device.setDeviceName(request.deviceName().trim());
        }
        if (request.deviceType() != null && !request.deviceType().isBlank()) {
            device.setDeviceType(request.deviceType().trim());
        }
        if (request.merchantId() != null) {
            if (request.merchantId().isBlank()) {
                device.setMerchantId(null);
            } else {
                String merchantId = request.merchantId().trim();
                requireMerchant(merchantId);
                merchantScopeService.requireMerchantAccess(operatorId, merchantId);
                device.setMerchantId(merchantId);
            }
        }
        boolean touchRefundPolicy = request.refundPolicy() != null;
        String storedRefundPolicy = null;
        if (touchRefundPolicy) {
            // INHERIT/空 → null（跟随全局）；MyBatis-Plus updateById 默认跳过 null，须单独 set
            storedRefundPolicy = RefundPolicyService.normalizeStored(request.refundPolicy());
            device.setRefundPolicy(storedRefundPolicy);
        }
        if (request.imei() != null) {
            device.setImei(trimToNull(request.imei()));
        }
        if (request.assetOwner() != null) {
            device.setAssetOwner(trimToNull(request.assetOwner()));
        }
        if (request.coopMode() != null) {
            device.setCoopMode(DeviceAssetService.normalizeCoop(request.coopMode()));
        }
        if (request.depositCents() != null) {
            device.setDepositCents(request.depositCents() < 0 ? 0L : request.depositCents());
        }
        if (request.dataFeeCents() != null) {
            device.setDataFeeCents(request.dataFeeCents() < 0 ? 0L : request.dataFeeCents());
        }
        if (request.opsTags() != null) {
            device.setOpsTags(trimToNull(request.opsTags()));
        }
        if (request.routeCode() != null) {
            device.setRouteCode(trimToNull(request.routeCode()));
        }
        if (request.lifecycleRemark() != null) {
            device.setLifecycleRemark(trimToNull(request.lifecycleRemark()));
        }
        if (request.latitude() != null) {
            device.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            device.setLongitude(request.longitude());
        }
        if (request.address() != null) {
            device.setAddress(trimToNull(request.address()));
        }
        Instant now = Instant.now();
        device.setUpdatedAt(now);
        deviceRepository.save(device);
        if (touchRefundPolicy) {
            deviceRepository.update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                    .eq(DeviceInfo::getDeviceId, deviceId)
                    .set(DeviceInfo::getRefundPolicy, storedRefundPolicy)
                    .set(DeviceInfo::getUpdatedAt, now));
            device.setRefundPolicy(storedRefundPolicy);
            device.setUpdatedAt(now);
        }
        auditService.record(operatorId, "DEVICE_UPDATE", "DEVICE", deviceId,
                device.getDeviceName() + "; refundPolicy=" + device.getRefundPolicy());
        // 重新加载，避免返回值与库不一致
        DeviceInfo fresh = deviceRepository.findById(deviceId).orElse(device);
        ShoppingSession session = findSessionForDeviceList(deviceId);
        return toDeviceDto(fresh, session, replenishingDeviceIds().contains(fresh.getDeviceId()));
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId) {
        return self.exportOrdersCsv(operatorId, deviceId, null, "orders",
                null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId, String status, String mode) {
        return self.exportOrdersCsv(operatorId, deviceId, status, mode,
                null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(
            Long operatorId,
            String deviceId,
            String status,
            String mode,
            String orderId,
            Long userId,
            String sessionId,
            String payTradeNo,
            String payChannel,
            Instant from,
            Instant to,
            String keyword) {
        permissionService.requirePermission(operatorId, "ops:order:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, CREATEDAT));
        Page<CabinetOrder> page = queryOrders(
                operatorId, deviceId, status, null, from, to,
                orderId, userId, sessionId, payTradeNo, payChannel, keyword, pageable);
        boolean byLines = mode != null && (mode.equalsIgnoreCase("lines") || mode.equalsIgnoreCase("product"));
        if (byLines) {
            StringBuilder sb = new StringBuilder(
                    "orderId,deviceId,status,skuId,skuName,quantity,unitPriceCents,lineAmountCents,createdAt\n");
            for (CabinetOrder o : page.getContent()) {
                List<CabinetOrderLine> lines = orderLineRepository.findByOrderId(o.getOrderId());
                if (lines.isEmpty()) {
                    sb.append(csv(o.getOrderId())).append(',')
                            .append(csv(o.getDeviceId())).append(',')
                            .append(csv(o.getStatus())).append(',')
                            .append(',').append(',').append("0,0,0,")
                            .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
                    continue;
                }
                for (CabinetOrderLine line : lines) {
                    sb.append(csv(o.getOrderId())).append(',')
                            .append(csv(o.getDeviceId())).append(',')
                            .append(csv(o.getStatus())).append(',')
                            .append(csv(line.getSkuId())).append(',')
                            .append(csv(line.getSkuName())).append(',')
                            .append(line.getQuantity()).append(',')
                            .append(line.getUnitPriceCents()).append(',')
                            .append(line.getLineAmountCents()).append(',')
                            .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
                }
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
        List<String> orderIds = page.getContent().stream().map(CabinetOrder::getOrderId).toList();
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(orderIds);
        Map<String, List<CabinetOrderLine>> linesByOrder = loadOrderLinesByOrderIds(orderIds);
        StringBuilder sb = new StringBuilder(
                "orderId,sessionId,userId,deviceId,merchantId,totalAmountCents,originalAmountCents,status,payChannel,"
                        + "payTradeNo,paymentOperationId,lineCount,lineSummary,inventoryDeducted,"
                        + "couponDiscountCents,memberDiscountCents,refundPolicy,refundedAt,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            AdminOrderSummaryDto row = toOrderSummary(
                    o,
                    qtyByOrder.getOrDefault(o.getOrderId(), 0),
                    linesByOrder.getOrDefault(o.getOrderId(), List.of()));
            sb.append(csv(row.orderId())).append(',')
                    .append(csv(row.sessionId())).append(',')
                    .append(row.userId()).append(',')
                    .append(csv(row.deviceId())).append(',')
                    .append(csv(row.merchantId())).append(',')
                    .append(row.totalAmountCents()).append(',')
                    .append(row.originalAmountCents()).append(',')
                    .append(csv(row.status())).append(',')
                    .append(csv(row.payChannel())).append(',')
                    .append(csv(row.payTradeNo())).append(',')
                    .append(csv(row.paymentOperationId())).append(',')
                    .append(row.lineCount()).append(',')
                    .append(csv(row.lineSummary())).append(',')
                    .append(row.inventoryDeducted()).append(',')
                    .append(row.couponDiscountCents()).append(',')
                    .append(row.memberDiscountCents()).append(',')
                    .append(csv(row.refundPolicy())).append(',')
                    .append(csv(row.refundedAt() == null ? "" : String.valueOf(row.refundedAt()))).append(',')
                    .append(csv(String.valueOf(row.createdAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        return exportSessionsCsv(operatorId, deviceId, state, null, null, null, null, null, false, 30);
    }

    public byte[] exportSessionsCsv(
            Long operatorId,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword) {
        return exportSessionsCsv(operatorId, deviceId, state, sessionId, userId, from, to, keyword, false, 30);
    }

    public byte[] exportSessionsCsv(
            Long operatorId,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword,
            boolean stuckOnly,
            int stuckMinutes) {
        permissionService.requirePermission(operatorId, "ops:session:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, CREATEDAT));
        Instant updatedBefore = stuckOnly
                ? Instant.now().minus(Math.max(stuckMinutes, 1), ChronoUnit.MINUTES)
                : null;
        Page<ShoppingSession> page = querySessions(
                operatorId, deviceId, state, sessionId, userId, from, to, keyword,
                null, updatedBefore, pageable);
        StringBuilder sb = new StringBuilder(
                "sessionId,userId,deviceId,state,sessionKind,entryChannel,orderId,uploadStatus,failReason,"
                        + "openTime,closeTime,createdAt,updatedAt\n");
        for (ShoppingSession s : page.getContent()) {
            sb.append(csv(s.getSessionId())).append(',')
                    .append(s.getUserId()).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getState()).append(',')
                    .append(csv(DeviceValidationService.sessionKind(s))).append(',')
                    .append(csv(s.getEntryChannel())).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(s.getUploadStatus())).append(',')
                    .append(csv(s.getFailReason())).append(',')
                    .append(csv(String.valueOf(s.getOpenTime()))).append(',')
                    .append(csv(String.valueOf(s.getCloseTime()))).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append(',')
                    .append(csv(String.valueOf(s.getUpdatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public AdminTrendDto orderTrend(Long operatorId) {
        return orderTrend(operatorId, 7);
    }

    public AdminTrendDto orderTrend(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            buckets.put(start.plusDays(i), new long[]{0, 0});
        }
        for (CabinetOrder order : queryTrendOrders(operatorId, since)) {
            LocalDate day = order.getCreatedAt().atZone(zone).toLocalDate();
            long[] bucket = buckets.get(day);
            if (bucket != null) {
                bucket[0]++;
                bucket[1] += order.getTotalAmountCents();
            }
        }
        List<AdminDailyStatDto> points = buckets.entrySet().stream()
                .map(e -> new AdminDailyStatDto(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1]))
                .toList();
        return new AdminTrendDto(points);
    }

    public AdminChannelBreakdownDto channelBreakdown(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant since = today.minusDays(window - 1L).atStartOfDay(zone).toInstant();

        Map<String, long[]> orderBuckets = new java.util.LinkedHashMap<>();
        for (CabinetOrder order : queryTrendOrders(operatorId, since)) {
            String channel = normalizePayChannel(order.getPayChannel());
            long[] bucket = orderBuckets.computeIfAbsent(channel, k -> new long[]{0, 0});
            bucket[0]++;
            bucket[1] += Math.max(order.getTotalAmountCents(), 0);
        }

        Map<String, long[]> rechargeBuckets = new java.util.LinkedHashMap<>();
        for (RechargeOrder recharge : rechargeOrderRepository.findByCreatedAtAfter(since)) {
            if (!"PAID".equalsIgnoreCase(recharge.getStatus())) {
                continue;
            }
            String channel = normalizePayChannel(recharge.getChannel());
            long[] bucket = rechargeBuckets.computeIfAbsent(channel, k -> new long[]{0, 0});
            bucket[0]++;
            bucket[1] += Math.max(recharge.getAmountCents(), 0);
        }

        return new AdminChannelBreakdownDto(
                toChannelStats(orderBuckets),
                toChannelStats(rechargeBuckets)
        );
    }

    private static List<AdminChannelStatDto> toChannelStats(Map<String, long[]> buckets) {
        return buckets.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .map(e -> new AdminChannelStatDto(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    private static String normalizePayChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "UNKNOWN";
        }
        return channel.trim().toUpperCase();
    }

    public AdminOpsTrendDto opsTrend(Long operatorId) {
        return opsTrend(operatorId, 7);
    }

    public AdminOpsTrendDto opsTrend(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            buckets.put(start.plusDays(i), new long[]{0, 0});
        }

        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        List<ShoppingSession> closedSessions = sessionRepository.findByStateInAndUpdatedAtAfter(
                CLOSED_STATES, since);
        for (ShoppingSession session : closedSessions) {
            if (scopedDevices != null && !scopedDevices.contains(session.getDeviceId())) {
                continue;
            }
            LocalDate day = session.getUpdatedAt().atZone(zone).toLocalDate();
            long[] bucket = buckets.get(day);
            if (bucket == null) {
                continue;
            }
            if (session.getState() == SessionState.COMPLETED) {
                bucket[0]++;
            } else if (session.getState() == SessionState.DISPUTED) {
                bucket[1]++;
            }
        }

        List<AdminOpsDailyDto> points = buckets.entrySet().stream()
                .map(e -> {
                    long completed = e.getValue()[0];
                    long disputed = e.getValue()[1];
                    long total = completed + disputed;
                    double recognitionRate = total > 0 ? (double) completed / total : 1.0;
                    double disputeRate = total > 0 ? (double) disputed / total : 0.0;
                    return new AdminOpsDailyDto(
                            e.getKey().toString(),
                            completed,
                            disputed,
                            recognitionRate,
                            disputeRate
                    );
                })
                .toList();
        return new AdminOpsTrendDto(points);
    }

    @Transactional
    public AdminUserDto adjustBalance(Long operatorId, Long userId, AdjustBalanceRequest request) {
        permissionService.requirePermission(operatorId, "ops:user:balance");
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_ADJUST_OPERATOR_BALANCE);
        }
        return runWithUserBalanceLock(userId, () -> {
            UserInfo user = userInfoRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
            var ledger = balanceLedgerService.change(userId, request.deltaCents(), "ADMIN_ADJUST",
                    "ADMIN-" + userId, "ADMIN:" + request.idempotencyKey().trim(), request.reason());
            auditService.record(operatorId, "BALANCE_ADJUST", "USER", String.valueOf(userId),
                    "delta=" + request.deltaCents() + " balance=" + ledger.getBalanceAfterCents()
                            + " reason=" + request.reason().trim());
            return toUserDto(user);
        });
    }

    static String userBalanceLockKey(long userId) {
        return "user:balance:" + userId;
    }

    private <T> T runWithUserBalanceLock(long userId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(userBalanceLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "余额处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(userBalanceLockKey(userId));
        }
    }

    @Transactional
    public AdminUserDto setUserVerified(Long operatorId, Long userId, VerifyUserRequest request) {
        permissionService.requirePermission(operatorId, "ops:user:verify");
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        user.setVerified(request.verified());
        if (request.realName() != null && !request.realName().isBlank()) {
            user.setName(request.realName().trim());
        }
        userInfoRepository.save(user);
        auditService.record(operatorId, request.verified() ? "USER_VERIFY" : "USER_UNVERIFY", "USER",
                String.valueOf(userId), "verified=" + request.verified());
        return toUserDto(user);
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listRecharges(Long operatorId, int page, int size,
                                                      String status, Long userId) {
        permissionService.requirePermission(operatorId, "ops:recharge:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, CREATEDAT));
        String st = (status == null || status.isBlank()) ? null : status.trim();
        Page<RechargeOrder> result = rechargeOrderRepository.search(st, userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toRechargeDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional
    public RechargeOrderDto refundRecharge(Long operatorId, String orderId, String reason) {
        permissionService.requirePermission(operatorId, "ops:recharge:edit");
        RechargeOrderDto result = paymentService.refundRecharge(orderId, reason);
        auditService.record(operatorId, "RECHARGE_REFUND", "RECHARGE", orderId,
                "userId=" + result.userId() + " amount=" + result.amountCents());
        return result;
    }

    private RechargeOrderDto toRechargeDto(RechargeOrder order) {
        return new RechargeOrderDto(
                order.getOrderId(),
                order.getUserId(),
                order.getAmountCents(),
                order.getChannel(),
                order.getStatus(),
                order.getWxPrepayId(),
                order.getWxTransactionId(),
                order.getAlipayTradeNo(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt()
        );
    }

    private AdminAuditLogDto toAuditDto(com.aicabinet.trade.domain.AdminAuditLog log, UserInfo operator) {
        Long opId = log.getOperatorId();
        String phone = operator != null ? operator.getPhoneNumber() : null;
        String name = operator != null ? operator.getName() : null;
        // 0 / 空：定时任务、心跳恢复等系统写入
        if (opId == null || opId <= 0L) {
            name = "系统";
            phone = null;
        } else if (name == null || name.isBlank()) {
            name = phone != null && !phone.isBlank() ? phone : ("账号 " + opId);
        }
        return new AdminAuditLogDto(
                log.getLogId(), opId, phone, name, log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetail(), log.getCreatedAt()
        );
    }

    private ShoppingSession findActiveSession(String deviceId) {
        return sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_STATES).stream()
                .findFirst().orElse(null);
    }

    private ShoppingSession findSessionForDeviceList(String deviceId) {
        ShoppingSession active = findActiveSession(deviceId);
        if (active != null) {
            return active;
        }
        return sessionRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private AdminUserDto toUserDto(UserInfo u) {
        int balance = userAccountRepository.findById(u.getUserId())
                .map(a -> a.getBalanceCents()).orElse(0);
        Member member = memberRepository.findByUserId(u.getUserId()).orElse(null);
        boolean blacklisted = blacklistRepository.findActiveByUserId(u.getUserId()).isPresent();
        return toUserDto(u, balance, member, blacklisted);
    }

    private AdminUserDto toUserDto(UserInfo u, int balance, Member member, boolean blacklisted) {
        String role = u.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START ? "OPERATOR" : "CONSUMER";
        return new AdminUserDto(
                u.getUserId(), u.getPhoneNumber(), resolveUserDisplayName(u), u.isVerified(),
                balance, role, u.getCreatedAt(),
                member != null ? member.getMemberLevel() : "NORMAL",
                member != null && member.getAvailablePoints() != null ? member.getAvailablePoints() : 0,
                blacklisted
        );
    }

    /** 列表展示名：优先实名/昵称，空则留 null 由前端显示「暂无」。 */
    private static String resolveUserDisplayName(UserInfo u) {
        if (u.getName() != null && !u.getName().isBlank()) {
            return u.getName().trim();
        }
        return null;
    }

    private static String csv(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Page<ShoppingSession> querySessions(
            Long operatorId,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword,
            Pageable pageable) {
        return querySessions(operatorId, deviceId, state, sessionId, userId, from, to, keyword,
                null, null, pageable);
    }

    private Page<ShoppingSession> querySessions(
            Long operatorId,
            String deviceId,
            SessionState state,
            String sessionId,
            Long userId,
            Instant from,
            Instant to,
            String keyword,
            String uploadStatus,
            Instant updatedBefore,
            Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        String deviceFilter = (deviceId != null && !deviceId.isBlank()) ? deviceId.trim() : null;
        Collection<String> scopeFilter = deviceFilter == null ? deviceScope : null;
        return sessionRepository.findByFiltersOrderByCreatedAtDesc(
                deviceFilter,
                scopeFilter,
                state,
                blankToNull(sessionId),
                userId,
                from,
                to,
                blankToNull(keyword),
                uploadStatus,
                updatedBefore,
                pageable);
    }

    private Page<CabinetOrder> queryOrders(
            Long operatorId, String deviceId, String status, Instant createdBefore, Pageable pageable) {
        return queryOrders(operatorId, deviceId, status, createdBefore, null, null,
                null, null, null, null, null, null, pageable);
    }

    private Page<CabinetOrder> queryOrders(
            Long operatorId,
            String deviceId,
            String status,
            Instant createdBefore,
            Instant createdFrom,
            Instant createdTo,
            String orderId,
            Long userId,
            String sessionId,
            String payTradeNo,
            String payChannel,
            String keyword,
            Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        String statusFilter = (status != null && !status.isBlank()) ? status.trim() : null;
        String deviceFilter = (deviceId != null && !deviceId.isBlank()) ? deviceId.trim() : null;
        Collection<String> scopeFilter = deviceFilter == null ? deviceScope : null;
        return orderRepository.findByFiltersOrderByCreatedAtDesc(
                deviceFilter,
                scopeFilter,
                statusFilter,
                createdBefore,
                createdFrom,
                createdTo,
                blankToNull(orderId),
                userId,
                blankToNull(sessionId),
                blankToNull(payTradeNo),
                blankToNull(payChannel),
                blankToNull(keyword),
                pageable);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Map<String, List<CabinetOrderLine>> loadOrderLinesByOrderIds(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return orderLineRepository.selectList(
                        Wrappers.<CabinetOrderLine>lambdaQuery().in(CabinetOrderLine::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(CabinetOrderLine::getOrderId));
    }

    private static String buildAdminLineSummary(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String preview = lines.stream()
                .limit(2)
                .map(l -> {
                    String name = (l.getSkuName() == null ? l.getSkuId() : l.getSkuName()) + " x" + l.getQuantity();
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo();
                    }
                    return name;
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (lines.size() > 2) {
            return preview + " 等" + lines.size() + "种";
        }
        return preview;
    }

    private List<CabinetOrder> queryTrendOrders(Long operatorId, Instant since) {
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return List.of();
        }
        if (scopedDevices == null) {
            return orderRepository.findByCreatedAtAfter(since);
        }
        return orderRepository.findByDeviceIdInAndCreatedAtAfter(scopedDevices, since);
    }

    private AdminDeviceDto toDeviceDto(DeviceInfo d, ShoppingSession active, boolean replenishmentInProgress) {
        String merchantName = null;
        if (d.getMerchantId() != null) {
            merchantName = merchantRepository.findById(d.getMerchantId())
                    .map(com.aicabinet.trade.domain.Merchant::getMerchantName)
                    .orElse(null);
        }
        return new AdminDeviceDto(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getDeviceType(),
                d.getOnlineStatus(),
                d.getMerchantId(),
                merchantName,
                active != null ? active.getSessionId() : null,
                active != null ? active.getState().name() : null,
                d.getUpdatedAt(),
                replenishmentInProgress,
                d.getRefundPolicy(),
                refundPolicyService.resolveForDevice(d.getDeviceId()).name(),
                d.salesLockedEnabled(),
                DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()),
                d.getImei(),
                d.getAssetOwner(),
                d.getCoopMode(),
                d.getDepositCents(),
                d.getDataFeeCents(),
                d.getOpsTags(),
                d.getRouteCode(),
                d.getDeployedAt(),
                d.getLifecycleRemark(),
                d.getLatitude(),
                d.getLongitude(),
                d.getAddress(),
                d.getId()
        );
    }

    @Transactional(readOnly = true)
    public List<com.aicabinet.common.dto.DeviceMapPointDto> listDeviceMapPoints(
            Long operatorId, String lifecycleStatus, String routeCode, String online) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DEVICE_LIST, "ops:device-map:view");
        String life = lifecycleStatus == null || lifecycleStatus.isBlank() ? DEPLOYED : lifecycleStatus.trim().toUpperCase();
        String route = routeCode == null ? "" : routeCode.trim();
        String onlineFilter = online == null ? "" : online.trim().toUpperCase();
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
        return devices.stream()
                .filter(d -> d.getLatitude() != null && d.getLongitude() != null)
                .filter(d -> "ALL".equals(life)
                        || life.equalsIgnoreCase(DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus())))
                .filter(d -> route.isEmpty() || route.equalsIgnoreCase(String.valueOf(d.getRouteCode())))
                .filter(d -> onlineFilter.isEmpty()
                        || onlineFilter.equalsIgnoreCase(String.valueOf(d.getOnlineStatus())))
                .map(d -> new com.aicabinet.common.dto.DeviceMapPointDto(
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getMerchantId(),
                        d.getOnlineStatus(),
                        DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()),
                        d.getRouteCode(),
                        d.salesLockedEnabled(),
                        d.getLatitude(),
                        d.getLongitude(),
                        d.getAddress()
                ))
                .toList();
    }

    private void requireMerchant(String merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
    }

    private AdminSessionDto toSessionDto(ShoppingSession s) {
        String previewUrl = minioVideoService.presignPlaybackUrl(s.getVideoUri()).orElse(null);
        Long shoppingMs = null;
        if (s.getOpenTime() != null && s.getCloseTime() != null) {
            shoppingMs = Math.max(0L, java.time.Duration.between(s.getOpenTime(), s.getCloseTime()).toMillis());
        }
        Long recognitionMs = null;
        if (s.getCloseTime() != null && s.getUpdatedAt() != null
                && !s.getUpdatedAt().isBefore(s.getCloseTime())) {
            recognitionMs = Math.max(0L, java.time.Duration.between(s.getCloseTime(), s.getUpdatedAt()).toMillis());
        }
        return new AdminSessionDto(
                s.getSessionId(), s.getUserId(), s.getDeviceId(), s.getState(),
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(), s.getVideoUri(),
                s.getUploadStatus(), s.getCameraFusionMode(), previewUrl,
                s.getFailReason(),
                s.getCreatedAt(), s.getUpdatedAt(),
                DeviceValidationService.sessionKind(s),
                s.getReplenishmentTaskId(),
                s.getEntryChannel(),
                s.getEntryChannel(),
                s.getPreauthCents() > 0 ? s.getPreauthCents() : null,
                s.getPreauthStatus(),
                shoppingMs,
                recognitionMs
        );
    }

    private AdminOrderSummaryDto toOrderSummary(CabinetOrder o, int lineCount, List<CabinetOrderLine> lines) {
        String payChannel = o.getPayChannel();
        // 余额账本扣款以 BL- 操作号为准，避免入口渠道误标为微信/支付宝
        if (o.getPaymentOperationId() != null && o.getPaymentOperationId().startsWith("BL-")) {
            payChannel = "BALANCE";
        }
        String merchantId = null;
        if (o.getDeviceId() != null) {
            merchantId = deviceRepository.findById(o.getDeviceId())
                    .map(DeviceInfo::getMerchantId)
                    .orElse(null);
        }
        int coupon = Math.max(0, o.getCouponDiscountCents());
        int member = Math.max(0, o.getMemberDiscountCents());
        int original = o.getOriginalAmountCents() > 0
                ? o.getOriginalAmountCents()
                : o.getTotalAmountCents() + coupon + member;
        String refundPolicy = null;
        try {
            refundPolicy = refundPolicyService.resolveForDevice(o.getDeviceId()).name();
        } catch (Exception ignored) {
            // leave null
        }
        return new AdminOrderSummaryDto(
                o.getOrderId(),
                o.getSessionId(),
                o.getUserId(),
                o.getDeviceId(),
                merchantId,
                o.getTotalAmountCents(),
                original,
                coupon,
                member,
                o.getStatus(),
                payChannel,
                lineCount,
                buildAdminLineSummary(lines),
                o.getPayTradeNo(),
                o.getPaymentOperationId(),
                o.getRefundedAt(),
                o.isInventoryDeducted(),
                refundPolicy,
                o.getCreatedAt()
        );
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

    private static int normalizeTrendDays(int days) {
        if (days >= 90) {
            return 90;
        }
        if (days >= 30) {
            return 30;
        }
        return 7;
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
