package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletResponse;
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

    private static final int EXPORT_LIMIT = 5000;
    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private static final List<SessionState> CLOSED_STATES = List.of(
            SessionState.COMPLETED, SessionState.DISPUTED
    );
    private static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "ACCRUED", "LEDGER_ONLY", "FAILED", "WECHAT_FAILED"
    );
    private static final List<String> SPLIT_EXCEPTION_STATUSES = List.of("FAILED", "WECHAT_FAILED", "LEDGER_ONLY");
    private static final long STALE_SESSION_MINUTES = 30;
    private static final long IN_TRANSIT_OVERDUE_HOURS = 24;

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
                                 RefundPolicyService refundPolicyService) {
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
    }

    public AdminStatsDto stats(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:dashboard:view", "ops:analytics:view");
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
                .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
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
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        List<OpsActionItemDto> items = new java.util.ArrayList<>();

        List<DisputeTicket> openDisputes = disputeRepository
                .findTop10ByStatusOrderBySlaDueAtAscCreatedAtAsc("OPEN").stream()
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
                .findTop10ByOnlineStatusNotOrderByUpdatedAtAsc("ONLINE").stream()
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

        inventoryRepository.findLowStock().stream()
                .filter(i -> inDeviceScope(scopedDevices, i.getId().getDeviceId()))
                .limit(10)
                .forEach(i -> items.add(new OpsActionItemDto(
                        "LOW_STOCK",
                        "MEDIUM",
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

        replenishmentTaskRepository.findTop10ByStatusInOrderByCreatedAtAsc(List.of("PENDING", "IN_PROGRESS")).stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .forEach(t -> items.add(new OpsActionItemDto(
                        "REPLENISHMENT",
                        "MEDIUM",
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
                        Math.abs(r.getDiffCents()) > 0 ? "HIGH" : "MEDIUM",
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
                                "FAILED".equalsIgnoreCase(s.getStatus()) || "WECHAT_FAILED".equalsIgnoreCase(s.getStatus())
                                        ? "HIGH" : "MEDIUM",
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
        inTransitRepository.findByStatusOrderByCreatedAtAsc("IN_TRANSIT").stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isBefore(transitCutoff))
                .limit(10)
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
                items.stream().limit(30).toList()
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
                deviceRepository.findAll().stream()
                        .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                        .count(),
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
        Set<String> occupied = new HashSet<>();
        sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .filter(s -> scopedDevices == null || scopedDevices.contains(s.getDeviceId()))
                .forEach(s -> occupied.add(s.getDeviceId()));
        replenishmentTaskRepository.findByStatusIn(List.of("IN_PROGRESS")).stream()
                .map(ReplenishmentTask::getDeviceId)
                .filter(deviceId -> scopedDevices == null || scopedDevices.contains(deviceId))
                .forEach(occupied::add);
        return occupied.size();
    }

    private long countOpenDisputes(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return disputeRepository.countByStatus("OPEN");
        }
        return disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()
                .filter(d -> inDeviceScope(scopedDevices, sessionDeviceId(d.getSessionId())))
                .count();
    }

    private long countOfflineDevices(Set<String> scopedDevices) {
        if (scopedDevices == null) {
            return deviceRepository.countByOnlineStatusNot("ONLINE");
        }
        return deviceRepository.findAll().stream()
                .filter(d -> scopedDevices.contains(d.getDeviceId()))
                .filter(d -> !"ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .count();
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
        return inventoryRepository.findLowStock().stream()
                .filter(i -> scopedDevices.contains(i.getId().getDeviceId()))
                .count();
    }

    private long countPendingReplenishments(Set<String> scopedDevices) {
        List<String> statuses = List.of("PENDING", "IN_PROGRESS");
        if (scopedDevices == null) {
            return replenishmentTaskRepository.countByStatusIn(statuses);
        }
        return replenishmentTaskRepository.findByStatusIn(statuses).stream()
                .filter(t -> scopedDevices.contains(t.getDeviceId()))
                .count();
    }

    private List<ShoppingSession> findStaleSessions(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES);
        return sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(cutoff))
                .limit(10)
                .toList();
    }

    private long countStaleSessions(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(STALE_SESSION_MINUTES, ChronoUnit.MINUTES);
        return sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(cutoff))
                .count();
    }

    private long countSplitExceptions(Set<String> scopedDevices) {
        return splitRepository.findAll().stream()
                .filter(s -> SPLIT_EXCEPTION_STATUSES.contains(s.getStatus()))
                .filter(s -> inDeviceScope(scopedDevices, s.getDeviceId()))
                .count();
    }

    private long countInTransitOverdue(Set<String> scopedDevices) {
        Instant cutoff = Instant.now().minus(IN_TRANSIT_OVERDUE_HOURS, ChronoUnit.HOURS);
        return inTransitRepository.findByStatusOrderByCreatedAtAsc("IN_TRANSIT").stream()
                .filter(t -> inDeviceScope(scopedDevices, t.getDeviceId()))
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isBefore(cutoff))
                .count();
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
        return "MEDIUM";
    }

    private static boolean inDeviceScope(Set<String> scopedDevices, String deviceId) {
        return scopedDevices == null || (deviceId != null && scopedDevices.contains(deviceId));
    }

    private static String disputeSeverity(DisputeTicket ticket) {
        if (ticket.getSlaDueAt() != null && !ticket.getSlaDueAt().isAfter(Instant.now())) {
            return "CRITICAL";
        }
        if ("URGENT".equalsIgnoreCase(ticket.getPriority())) {
            return "CRITICAL";
        }
        if ("HIGH".equalsIgnoreCase(ticket.getPriority())) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private static String uploadSeverity(ShoppingSession session) {
        return session.getUpdatedAt().isBefore(Instant.now().minus(30, ChronoUnit.MINUTES))
                ? "HIGH" : "MEDIUM";
    }

    private static String offlineSeverity(DeviceInfo device) {
        Instant updated = device.getUpdatedAt();
        return updated != null && updated.isBefore(Instant.now().minus(2, ChronoUnit.HOURS))
                ? "HIGH" : "MEDIUM";
    }

    private static int severityRank(String severity) {
        return switch (String.valueOf(severity).toUpperCase()) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    private Set<String> replenishingDeviceIds() {
        return replenishmentTaskRepository.findByStatusIn(List.of("IN_PROGRESS")).stream()
                .map(ReplenishmentTask::getDeviceId)
                .collect(Collectors.toSet());
    }

    public List<AdminDeviceDto> listDevices(Long operatorId) {
        return listDevicesPaged(operatorId, 0, 5000, null, null).items();
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
        Set<String> replenishing = replenishingDeviceIds();
        Map<String, ShoppingSession> sessionByDevice = sessionRepository.findAll().stream()
                .collect(Collectors.toMap(
                        ShoppingSession::getDeviceId,
                        s -> s,
                        AdminDashboardService::preferSessionForDeviceList
                ));

        String kw = q == null ? "" : q.trim().toLowerCase();
        String onlineFilter = online == null ? "" : online.trim().toUpperCase();

        List<AdminDeviceDto> filtered = devices.stream()
                .map(d -> toDeviceDto(d, sessionByDevice.get(d.getDeviceId()), replenishing.contains(d.getDeviceId())))
                .filter(d -> {
                    if (!onlineFilter.isEmpty() && !onlineFilter.equalsIgnoreCase(String.valueOf(d.onlineStatus()))) {
                        return false;
                    }
                    if (kw.isEmpty()) {
                        return true;
                    }
                    return String.valueOf(d.deviceId()).toLowerCase().contains(kw)
                            || String.valueOf(d.deviceName() == null ? "" : d.deviceName()).toLowerCase().contains(kw)
                            || String.valueOf(d.merchantId() == null ? "" : d.merchantId()).toLowerCase().contains(kw)
                            || String.valueOf(d.merchantName() == null ? "" : d.merchantName()).toLowerCase().contains(kw);
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
        permissionService.requireAnyPermission(operatorId, "ops:session:list", "ops:session:upload");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ShoppingSession> result = querySessions(operatorId, deviceId, state, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSessionDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, int page, int size, String deviceId) {
        return listOrders(operatorId, page, size, deviceId, null);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CabinetOrder> result = queryOrders(operatorId, deviceId, status, pageable);
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(
                result.getContent().stream().map(CabinetOrder::getOrderId).toList());
        return new PageResult<>(
                result.getContent().stream()
                        .map(o -> toOrderSummary(o, qtyByOrder.getOrDefault(o.getOrderId(), 0)))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
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
        if (EnumSet.of(SessionState.COMPLETED, SessionState.CANCELLED).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_FINISHED);
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
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
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

    public PageResult<AdminAuditLogDto> listAuditLogs(Long operatorId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:audit:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<com.aicabinet.trade.domain.AdminAuditLog> result =
                auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
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
        return new PageResult<>(
                result.getContent().stream().map(this::toUserDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sku:list");
        return skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .map(SkuCatalog::toDto)
                .toList();
    }

    @Transactional
    public SkuCatalogDto createSku(Long operatorId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        if (skuCatalogRepository.existsById(request.skuId().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_EXISTS);
        }
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(request.skuId().trim());
        applySkuRequest(sku, request);
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_CREATE", "SKU", sku.getSkuId(),
                sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        applySkuRequest(sku, request);
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_UPDATE", "SKU", sku.getSkuId(),
                sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
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
        permissionService.requirePermission(operatorId, "ops:order:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CabinetOrder> page = queryOrders(operatorId, deviceId, pageable);
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(
                page.getContent().stream().map(CabinetOrder::getOrderId).toList());
        StringBuilder sb = new StringBuilder("orderId,sessionId,userId,deviceId,totalAmountCents,status,lineCount,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            sb.append(csv(o.getOrderId())).append(',')
                    .append(csv(o.getSessionId())).append(',')
                    .append(o.getUserId()).append(',')
                    .append(csv(o.getDeviceId())).append(',')
                    .append(o.getTotalAmountCents()).append(',')
                    .append(csv(o.getStatus())).append(',')
                    .append(qtyByOrder.getOrDefault(o.getOrderId(), 0)).append(',')
                    .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        permissionService.requirePermission(operatorId, "ops:session:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ShoppingSession> page = querySessions(operatorId, deviceId, state, pageable);
        StringBuilder sb = new StringBuilder("sessionId,userId,deviceId,state,orderId,openTime,closeTime,createdAt\n");
        for (ShoppingSession s : page.getContent()) {
            sb.append(csv(s.getSessionId())).append(',')
                    .append(s.getUserId()).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getState()).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(String.valueOf(s.getOpenTime()))).append(',')
                    .append(csv(String.valueOf(s.getCloseTime()))).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public AdminTrendDto orderTrend(Long operatorId) {
        return orderTrend(operatorId, 7);
    }

    public AdminTrendDto orderTrend(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, "ops:dashboard:view", "ops:analytics:view");
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
        permissionService.requireAnyPermission(operatorId, "ops:dashboard:view", "ops:analytics:view");
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
        permissionService.requireAnyPermission(operatorId, "ops:dashboard:view", "ops:analytics:view");
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
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        var ledger = balanceLedgerService.change(userId, request.deltaCents(), "ADMIN_ADJUST",
                "ADMIN-" + userId, "ADMIN:" + request.idempotencyKey().trim(), request.reason());
        auditService.record(operatorId, "BALANCE_ADJUST", "USER", String.valueOf(userId),
                "delta=" + request.deltaCents() + " balance=" + ledger.getBalanceAfterCents()
                        + " reason=" + request.reason().trim());
        return toUserDto(user);
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
                Sort.by(Sort.Direction.DESC, "createdAt"));
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
        String phone = operator != null ? operator.getPhoneNumber() : null;
        String name = operator != null ? operator.getName() : null;
        return new AdminAuditLogDto(
                log.getLogId(), log.getOperatorId(), phone, name, log.getAction(),
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
        String role = u.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START ? "OPERATOR" : "CONSUMER";
        return new AdminUserDto(
                u.getUserId(), u.getPhoneNumber(), u.getName(), u.isVerified(),
                balance, role, u.getCreatedAt()
        );
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

    private Page<ShoppingSession> querySessions(Long operatorId, String deviceId, SessionState state, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        String dev = hasDevice ? deviceId.trim() : null;
        if (hasDevice && state != null) {
            return sessionRepository.findByDeviceIdAndStateOrderByCreatedAtDesc(dev, state, pageable);
        }
        if (hasDevice) {
            return sessionRepository.findByDeviceIdOrderByCreatedAtDesc(dev, pageable);
        }
        if (deviceScope != null) {
            if (state != null) {
                return sessionRepository.findByDeviceIdInAndStateOrderByCreatedAtDesc(deviceScope, state, pageable);
            }
            return sessionRepository.findByDeviceIdInOrderByCreatedAtDesc(deviceScope, pageable);
        }
        if (state != null) {
            return sessionRepository.findByStateOrderByCreatedAtDesc(state, pageable);
        }
        return sessionRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private Page<CabinetOrder> queryOrders(Long operatorId, String deviceId, Pageable pageable) {
        return queryOrders(operatorId, deviceId, null, pageable);
    }

    private Page<CabinetOrder> queryOrders(
            Long operatorId, String deviceId, String status, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        String statusFilter = (status != null && !status.isBlank()) ? status.trim() : null;
        if (deviceId != null && !deviceId.isBlank()) {
            return orderRepository.findByFiltersOrderByCreatedAtDesc(
                    deviceId.trim(), null, statusFilter, pageable);
        }
        if (deviceScope != null) {
            return orderRepository.findByFiltersOrderByCreatedAtDesc(
                    null, deviceScope, statusFilter, pageable);
        }
        return orderRepository.findByFiltersOrderByCreatedAtDesc(null, null, statusFilter, pageable);
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
                refundPolicyService.resolveForDevice(d.getDeviceId()).name()
        );
    }

    private void requireMerchant(String merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
    }

    private AdminSessionDto toSessionDto(ShoppingSession s) {
        String previewUrl = minioVideoService.presignPlaybackUrl(s.getVideoUri()).orElse(null);
        return new AdminSessionDto(
                s.getSessionId(), s.getUserId(), s.getDeviceId(), s.getState(),
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(), s.getVideoUri(),
                s.getUploadStatus(), s.getCameraFusionMode(), previewUrl,
                s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    private AdminOrderSummaryDto toOrderSummary(CabinetOrder o, int lineCount) {
        String payChannel = o.getPayChannel();
        // 余额账本扣款以 BL- 操作号为准，避免入口渠道误标为微信/支付宝
        if (o.getPaymentOperationId() != null && o.getPaymentOperationId().startsWith("BL-")) {
            payChannel = "BALANCE";
        }
        return new AdminOrderSummaryDto(
                o.getOrderId(), o.getSessionId(), o.getUserId(), o.getDeviceId(),
                o.getTotalAmountCents(), o.getStatus(), payChannel,
                lineCount, o.getCreatedAt()
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
            case "FAILED" -> "上传失败";
            default -> status;
        };
    }

    private static String replenishStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status.toUpperCase()) {
            case "PENDING" -> "待处理";
            case "IN_PROGRESS" -> "进行中";
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
            case "PENDING" -> "待分账";
            case "SETTLED" -> "已分账";
            case "FAILED", "WECHAT_FAILED" -> "分账失败";
            case "LEDGER_ONLY" -> "仅记账";
            default -> status;
        };
    }
}
