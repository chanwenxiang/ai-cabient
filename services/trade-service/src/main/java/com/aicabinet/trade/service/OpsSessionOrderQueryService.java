package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
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
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营会话/订单查询与导出（原 AdminDashboardService 会话订单簇）。
 * Pass 3F：Catalog/Device 之后切片。
 */
@Service
public class OpsSessionOrderQueryService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpsSessionOrderQueryService.class);

    private static final int EXPORT_LIMIT = 5000;
    /** 工作台「待支付」与订单页 overdue=1 对齐：超过该分钟仍 PENDING 计入。 */
    public static final int UNPAID_OPS_OVERDUE_MINUTES = 30;
    private static final String STATUS_PENDING = "PENDING";
    private static final String CREATEDAT = "createdAt";
    /** 与工作台 staleSessions / 前端「仅滞留」一致：仅未终态活跃会话。 */
    static final List<SessionState> STUCK_ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final SettlementService settlementService;
    private final AdminAuditService auditService;
    private final PaymentService paymentService;
    private final RefundPolicyService refundPolicyService;
    private final OrderViewAssembler orderViewAssembler;
    private final SessionService sessionService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DisputeService disputeService;

    public OpsSessionOrderQueryService(PermissionService permissionService,
                                       MerchantScopeService merchantScopeService,
                                       ShoppingSessionMapper sessionRepository,
                                       CabinetOrderMapper orderRepository,
                                       CabinetOrderLineMapper orderLineMapper,
                                       OrderRevenueSplitMapper splitRepository,
                                       SettlementService settlementService,
                                       AdminAuditService auditService,
                                       PaymentService paymentService,
                                       RefundPolicyService refundPolicyService,
                                       OrderViewAssembler orderViewAssembler,
                                       SessionService sessionService,
                                       ConsumerPreauthService consumerPreauthService,
                                       @org.springframework.context.annotation.Lazy DisputeService disputeService) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineMapper;
        this.splitRepository = splitRepository;
        this.settlementService = settlementService;
        this.auditService = auditService;
        this.paymentService = paymentService;
        this.refundPolicyService = refundPolicyService;
        this.orderViewAssembler = orderViewAssembler;
        this.sessionService = sessionService;
        this.consumerPreauthService = consumerPreauthService;
        this.disputeService = disputeService;
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, int page, int size,
                                                      String deviceId, SessionState state) {
        return listSessions(operatorId, new SessionListQuery(
                page, size, deviceId, state, null, null, null, null, null, false, 30, null));
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, SessionListQuery query) {
        permissionService.requirePermission(operatorId, "ops:session:list");
        Pageable pageable = PageRequest.of(query.page(), Math.min(query.size(), 100));
        Instant updatedBefore = query.stuckOnly()
                ? Instant.now().minus(Math.max(query.stuckMinutes(), 1), ChronoUnit.MINUTES)
                : null;
        // 滞留 = 活跃态 + updated_at 早于阈值；禁止只按时间，否则已完成历史单全进「仅滞留」
        Collection<SessionState> stuckStates = query.stuckOnly() && query.state() == null
                ? STUCK_ACTIVE_STATES
                : null;
        Page<ShoppingSession> result = querySessions(
                operatorId,
                new SessionQueryCriteria(
                        query.deviceId(), query.state(), stuckStates, query.sessionId(), query.userId(),
                        query.from(), query.to(), query.keyword(), updatedBefore, query.sessionKind()),
                pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSessionDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public record SessionListQuery(
            int page, int size, String deviceId, SessionState state,
            String sessionId, Long userId, Instant from, Instant to, String keyword,
            boolean stuckOnly, int stuckMinutes,
            /** 会话类型（CONSUMER/RESTOCK/OPS/STOCKTAKE），MA-007 起服务端化；空=不筛。 */
            String sessionKind) {}

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listOrders(Long operatorId, int page, int size, String deviceId) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, null, false, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listOrders(
            Long operatorId, int page, int size, String deviceId, String status) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, status, false, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listOrders(
            Long operatorId, int page, int size, String deviceId, String status, boolean overdueOnly) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, status, overdueOnly, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listOrders(Long operatorId, OrderListQuery query) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        Pageable pageable = PageRequest.of(query.page(), Math.min(query.size(), 100));
        String status = query.status();
        Instant createdBefore = null;
        if (query.overdueOnly()) {
            createdBefore = Instant.now().minus(UNPAID_OPS_OVERDUE_MINUTES, ChronoUnit.MINUTES);
            if (status == null || status.isBlank()) {
                status = STATUS_PENDING;
            }
        }
        Page<CabinetOrder> result = queryOrders(
                operatorId,
                new OrderQueryCriteria(
                        query.deviceId(), status, createdBefore, query.from(), query.to(),
                        query.orderId(), query.userId(), query.sessionId(),
                        query.payTradeNo(), query.payChannel(), query.keyword(),
                        query.excludeZeroAmount()),
                pageable);
        List<String> orderIds = result.getContent().stream().map(CabinetOrder::getOrderId).toList();
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(orderIds);
        Map<String, List<CabinetOrderLine>> linesByOrder = loadOrderLinesByOrderIds(orderIds);
        Map<String, String> splitStatusByOrder = loadSplitStatusByOrderIds(orderIds);
        return new PageResult<>(
                result.getContent().stream()
                        .map(o -> toOrderSummary(
                                o,
                                qtyByOrder.getOrDefault(o.getOrderId(), 0),
                                linesByOrder.getOrDefault(o.getOrderId(), List.of()),
                                splitStatusByOrder.get(o.getOrderId())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public record OrderListQuery(
            int page, int size, String deviceId, String status, boolean overdueOnly,
            String orderId, Long userId, String sessionId, String payTradeNo, String payChannel,
            Instant from, Instant to, String keyword, boolean excludeZeroAmount) {}

    public long countOverdueUnpaidOrders(Long operatorId) {
        Instant cutoff = Instant.now().minus(UNPAID_OPS_OVERDUE_MINUTES, ChronoUnit.MINUTES);
        return queryOrders(operatorId, null, STATUS_PENDING, cutoff, PageRequest.of(0, 1)).getTotalElements();
    }

    @Transactional(readOnly = true)
    public OrderReadModel getOrder(Long operatorId, String orderId) {
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
        if (previous == SessionState.SHOPPING) {
            // R20-7（2026-11-12）：购物中直接取消=免单——用户已把货拿走，会话被置 CANCELLED 后
            // 既无订单也无争议单，且预授权 FROZEN 没有清扫器覆盖（FAILED 清扫器只扫 FAILED）。
            // 与 C09/开门超时同口径：先释放 hold，再转 DISPUTED 并建争议工单，由异常中心
            // 按视频/识别结果人工判定收款还是免单。
            cancelShoppingSessionAsDispute(operatorId, session);
            return toSessionDto(session);
        }
        // CREATED/OPENING：门未开或刚开门，取消合法；但必须释放预授权，否则 FROZEN 悬挂
        consumerPreauthService.releaseIfFrozen(session);
        sessionService.transition(session, SessionState.CANCELLED);
        auditService.appendLog(operatorId, "SESSION_CANCEL", "SESSION", sessionId,
                "device=" + session.getDeviceId() + " previous=" + previous);
        return toSessionDto(session);
    }

    /** R20-7：购物中会话的运营取消 → 转争议（可收可退），不做免单。 */
    private void cancelShoppingSessionAsDispute(Long operatorId, ShoppingSession session) {
        String sessionId = session.getSessionId();
        String reason = "运营尝试取消购物中会话，转人工核验是否已取货";
        try {
            consumerPreauthService.releaseIfFrozen(session);
        } catch (Exception e) {
            log.error("ops cancel 释放预授权失败，继续转争议 sessionId={}", sessionId, e);
        }
        session.setFailReason(reason);
        sessionService.transition(session, SessionState.DISPUTED);
        try {
            disputeService.createTimeoutTicket(session, reason);
        } catch (Exception ticketEx) {
            log.warn("ops cancel 转争议建单失败 sessionId={}", sessionId, ticketEx);
        }
        auditService.appendLog(operatorId, "SESSION_CANCEL_TO_DISPUTE", "SESSION", sessionId,
                "device=" + session.getDeviceId() + " 购物中取消转争议（R20-7）：" + reason);
        log.warn("ops cancel shopping session converted to dispute session={} device={} operator={}",
                sessionId, session.getDeviceId(), operatorId);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId) {
        return exportOrdersCsv(operatorId, new OrderExportQuery(
                deviceId, null, "orders", null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId, String status, String mode) {
        return exportOrdersCsv(operatorId, new OrderExportQuery(
                deviceId, status, mode, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, OrderExportQuery query) {
        permissionService.requirePermission(operatorId, "ops:order:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, CREATEDAT));
        Page<CabinetOrder> page = queryOrders(
                operatorId,
                new OrderQueryCriteria(
                        query.deviceId(), query.status(), null, query.from(), query.to(),
                        query.orderId(), query.userId(), query.sessionId(),
                        query.payTradeNo(), query.payChannel(), query.keyword(),
                        query.excludeZeroAmount()),
                pageable);
        boolean byLines = query.mode() != null
                && (query.mode().equalsIgnoreCase("lines") || query.mode().equalsIgnoreCase("product"));
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
        Map<String, String> splitStatusByOrder = loadSplitStatusByOrderIds(orderIds);
        StringBuilder sb = new StringBuilder(
                "orderId,sessionId,userId,deviceId,merchantId,totalAmountCents,originalAmountCents,status,payChannel,"
                        + "payTradeNo,paymentOperationId,lineCount,lineSummary,inventoryDeducted,"
                        + "couponDiscountCents,memberDiscountCents,refundPolicy,refundedCents,refundedAt,createdAt,splitStatus\n");
        for (CabinetOrder o : page.getContent()) {
            OrderReadModel row = toOrderSummary(
                    o,
                    qtyByOrder.getOrDefault(o.getOrderId(), 0),
                    linesByOrder.getOrDefault(o.getOrderId(), List.of()),
                    splitStatusByOrder.get(o.getOrderId()));
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
                    .append(row.refundedCents()).append(',')
                    .append(csv(row.refundedAt() == null ? "" : String.valueOf(row.refundedAt()))).append(',')
                    .append(csv(String.valueOf(row.createdAt()))).append(',')
                    .append(csv(row.splitStatus() == null ? "" : row.splitStatus())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public record OrderExportQuery(
            String deviceId, String status, String mode,
            String orderId, Long userId, String sessionId, String payTradeNo, String payChannel,
            Instant from, Instant to, String keyword, boolean excludeZeroAmount) {}

    public record SessionExportQuery(
            String deviceId, SessionState state,
            String sessionId, Long userId, Instant from, Instant to, String keyword,
            boolean stuckOnly, int stuckMinutes,
            /** 与列表同一套类型口径（MA-007）：导出必须跟筛选走，否则导出结果与页面不一致。 */
            String sessionKind) {}

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        return exportSessionsCsv(operatorId, new SessionExportQuery(
                deviceId, state, null, null, null, null, null, false, 30, null));
    }

    public byte[] exportSessionsCsv(Long operatorId, SessionExportQuery query) {
        permissionService.requirePermission(operatorId, "ops:session:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, CREATEDAT));
        Instant updatedBefore = query.stuckOnly()
                ? Instant.now().minus(Math.max(query.stuckMinutes(), 1), ChronoUnit.MINUTES)
                : null;
        Collection<SessionState> stuckStates = query.stuckOnly() && query.state() == null
                ? STUCK_ACTIVE_STATES
                : null;
        Page<ShoppingSession> page = querySessions(
                operatorId,
                new SessionQueryCriteria(
                        query.deviceId(), query.state(), stuckStates, query.sessionId(), query.userId(),
                        query.from(), query.to(), query.keyword(), updatedBefore, query.sessionKind()),
                pageable);
        StringBuilder sb = new StringBuilder(
                "sessionId,userId,deviceId,state,sessionKind,entryChannel,orderId,failReason,"
                        + "openTime,closeTime,createdAt,updatedAt\n");
        for (ShoppingSession s : page.getContent()) {
            sb.append(csv(s.getSessionId())).append(',')
                    .append(s.getUserId()).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getState()).append(',')
                    .append(csv(DeviceValidationService.sessionKind(s))).append(',')
                    .append(csv(s.getEntryChannel())).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(s.getFailReason())).append(',')
                    .append(csv(String.valueOf(s.getOpenTime()))).append(',')
                    .append(csv(String.valueOf(s.getCloseTime()))).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append(',')
                    .append(csv(String.valueOf(s.getUpdatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        // S3/审计 P1-7：统一走 CsvCells——补公式注入中和与 \r 覆盖（订单/开门记录导出，流量最大）
        return com.aicabinet.trade.support.CsvCells.escape(value);
    }

    private record SessionQueryCriteria(
            String deviceId, SessionState state, Collection<SessionState> states,
            String sessionId, Long userId,
            Instant from, Instant to, String keyword, Instant updatedBefore, String sessionKind) {}

    private record OrderQueryCriteria(
            String deviceId, String status, Instant createdBefore, Instant createdFrom, Instant createdTo,
            String orderId, Long userId, String sessionId, String payTradeNo, String payChannel,
            String keyword, boolean excludeZeroAmount) {}

    private Page<ShoppingSession> querySessions(
            Long operatorId, SessionQueryCriteria criteria, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, criteria.deviceId());
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        String deviceFilter = (criteria.deviceId() != null && !criteria.deviceId().isBlank())
                ? criteria.deviceId().trim() : null;
        Collection<String> scopeFilter = deviceFilter == null ? deviceScope : null;
        return sessionRepository.findByFiltersOrderByUpdatedAtDesc(
                new ShoppingSessionMapper.SessionFilterCriteria(
                        deviceFilter,
                        scopeFilter,
                        criteria.state(),
                        criteria.states(),
                        blankToNull(criteria.sessionId()),
                        criteria.userId(),
                        criteria.from(),
                        criteria.to(),
                        blankToNull(criteria.keyword()),
                        criteria.updatedBefore(),
                        criteria.sessionKind()),
                pageable);
    }

    private Page<CabinetOrder> queryOrders(
            Long operatorId, String deviceId, String status, Instant createdBefore, Pageable pageable) {
        return queryOrders(operatorId,
                new OrderQueryCriteria(deviceId, status, createdBefore, null, null,
                        null, null, null, null, null, null, false),
                pageable);
    }

    private Page<CabinetOrder> queryOrders(
            Long operatorId, OrderQueryCriteria criteria, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, criteria.deviceId());
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        String statusFilter = (criteria.status() != null && !criteria.status().isBlank())
                ? criteria.status().trim() : null;
        String deviceFilter = (criteria.deviceId() != null && !criteria.deviceId().isBlank())
                ? criteria.deviceId().trim() : null;
        Collection<String> scopeFilter = deviceFilter == null ? deviceScope : null;
        return orderRepository.findByFiltersOrderByCreatedAtDesc(
                new CabinetOrderMapper.OrderFilterCriteria(
                        deviceFilter,
                        scopeFilter,
                        statusFilter,
                        criteria.createdBefore(),
                        criteria.createdFrom(),
                        criteria.createdTo(),
                        blankToNull(criteria.orderId()),
                        criteria.userId(),
                        blankToNull(criteria.sessionId()),
                        blankToNull(criteria.payTradeNo()),
                        blankToNull(criteria.payChannel()),
                        blankToNull(criteria.keyword()),
                        criteria.excludeZeroAmount()),
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

    static String buildAdminLineSummary(List<CabinetOrderLine> lines) {
        return OrderViewAssembler.buildLineSummary(lines);
    }

    private AdminSessionDto toSessionDto(ShoppingSession s) {
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
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(),
                s.getFailReason(),
                s.getCreatedAt(), s.getUpdatedAt(),
                DeviceValidationService.sessionKind(s),
                s.getReplenishmentTaskId(),
                s.getEntryChannel(),
                s.getEntryChannel(),
                s.getPreauthCents() > 0 ? s.getPreauthCents() : null,
                s.getPreauthStatus(),
                shoppingMs,
                recognitionMs,
                s.getDeviceName()
        );
    }

    private OrderReadModel toOrderSummary(
            CabinetOrder o, int lineCount, List<CabinetOrderLine> lines, String splitStatus) {
        return orderViewAssembler.assembleSummary(
                o,
                lines,
                splitStatus,
                resolvePaidAt(o),
                resolveRefundPolicyName(o.getDeviceId()),
                resolvePayTradeNo(o));
    }

    private Map<String, String> loadSplitStatusByOrderIds(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return splitRepository.findByOrderIdIn(orderIds).stream()
                .filter(s -> s.getOrderId() != null && s.getStatus() != null && !s.getStatus().isBlank())
                .collect(Collectors.toMap(OrderRevenueSplit::getOrderId, OrderRevenueSplit::getStatus, (a, b) -> a));
    }

    /** 优先订单上的渠道流水号；空则回退支付操作上的网关单号。 */
    private String resolvePayTradeNo(CabinetOrder o) {
        if (o.getPayTradeNo() != null && !o.getPayTradeNo().isBlank()) {
            return o.getPayTradeNo();
        }
        String opId = o.getPaymentOperationId();
        if (opId == null || opId.isBlank()) {
            return null;
        }
        try {
            return paymentService.findGatewayTradeNo(opId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 已产生扣款的订单返回支付完成时间：优先支付操作创建时间，否则回退订单创建时间。
     * 待支付/关单/失败返回 null。
     */
    private Instant resolvePaidAt(CabinetOrder o) {
        String status = o.getStatus();
        if (status == null
                || STATUS_PENDING.equals(status)
                || "CANCELLED".equals(status)
                || CabinetConstants.ORDER_STATUS_FAILED.equals(status)) {
            return null;
        }
        String opId = o.getPaymentOperationId();
        if (opId != null && !opId.isBlank()) {
            try {
                Instant at = paymentService.findOperationCreatedAt(opId).orElse(null);
                if (at != null) {
                    return at;
                }
            } catch (Exception e) {
                // 审计批次4：吞异常会让对账口径静默回退到「下单时间」——至少要留错误痕迹
                log.error("resolve paidAt failed orderId={} opId={} — falling back to createdAt", o.getOrderId(), opId, e);
            }
        }
        return o.getCreatedAt();
    }

    private String resolveRefundPolicyName(String deviceId) {
        try {
            return refundPolicyService.resolveForDevice(deviceId).name();
        } catch (Exception ignored) {
            return null;
        }
    }

}
