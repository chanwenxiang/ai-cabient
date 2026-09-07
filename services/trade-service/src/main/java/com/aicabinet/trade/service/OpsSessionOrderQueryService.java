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
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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

    private static final int EXPORT_LIMIT = 5000;
    /** 工作台「待支付」与订单页 overdue=1 对齐：超过该分钟仍 PENDING 计入。 */
    public static final int UNPAID_OPS_OVERDUE_MINUTES = 30;
    private static final String STATUS_PENDING = "PENDING";
    private static final String CREATEDAT = "createdAt";

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final SettlementService settlementService;
    private final AdminAuditService auditService;
    private final MinioVideoService minioVideoService;
    private final PaymentService paymentService;
    private final RefundPolicyService refundPolicyService;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;

    public OpsSessionOrderQueryService(PermissionService permissionService,
                                       MerchantScopeService merchantScopeService,
                                       ShoppingSessionMapper sessionRepository,
                                       CabinetOrderMapper orderRepository,
                                       CabinetOrderLineMapper orderLineRepository,
                                       OrderRevenueSplitMapper splitRepository,
                                       SettlementService settlementService,
                                       AdminAuditService auditService,
                                       MinioVideoService minioVideoService,
                                       PaymentService paymentService,
                                       RefundPolicyService refundPolicyService,
                                       DeviceInfoMapper deviceRepository,
                                       MerchantMapper merchantRepository) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.splitRepository = splitRepository;
        this.settlementService = settlementService;
        this.auditService = auditService;
        this.minioVideoService = minioVideoService;
        this.paymentService = paymentService;
        this.refundPolicyService = refundPolicyService;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, int page, int size,
                                                      String deviceId, SessionState state) {
        return listSessions(operatorId, new SessionListQuery(
                page, size, deviceId, state, null, null, null, null, null, null, false, 30));
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, SessionListQuery query) {
        permissionService.requireAnyPermission(operatorId, "ops:session:list", "ops:session:upload");
        Pageable pageable = PageRequest.of(query.page(), Math.min(query.size(), 100));
        Instant updatedBefore = query.stuckOnly()
                ? Instant.now().minus(Math.max(query.stuckMinutes(), 1), ChronoUnit.MINUTES)
                : null;
        Page<ShoppingSession> result = querySessions(
                operatorId,
                new SessionQueryCriteria(
                        query.deviceId(), query.state(), query.sessionId(), query.userId(),
                        query.from(), query.to(), query.keyword(), blankToNull(query.uploadStatus()), updatedBefore),
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
            String uploadStatus, boolean stuckOnly, int stuckMinutes) {}

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, int page, int size, String deviceId) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, null, false, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, status, false, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status, boolean overdueOnly) {
        return listOrders(operatorId, new OrderListQuery(
                page, size, deviceId, status, overdueOnly, null, null, null, null, null, null, null, null, false));
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, OrderListQuery query) {
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
        auditService.appendLog(operatorId, "SESSION_CANCEL", "SESSION", sessionId,
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
            AdminOrderSummaryDto row = toOrderSummary(
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
            boolean stuckOnly, int stuckMinutes) {}

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        return exportSessionsCsv(operatorId, new SessionExportQuery(
                deviceId, state, null, null, null, null, null, false, 30));
    }

    public byte[] exportSessionsCsv(Long operatorId, SessionExportQuery query) {
        permissionService.requirePermission(operatorId, "ops:session:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, CREATEDAT));
        Instant updatedBefore = query.stuckOnly()
                ? Instant.now().minus(Math.max(query.stuckMinutes(), 1), ChronoUnit.MINUTES)
                : null;
        Page<ShoppingSession> page = querySessions(
                operatorId,
                new SessionQueryCriteria(
                        query.deviceId(), query.state(), query.sessionId(), query.userId(),
                        query.from(), query.to(), query.keyword(), null, updatedBefore),
                pageable);
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

    private static String csv(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private record SessionQueryCriteria(
            String deviceId, SessionState state, String sessionId, Long userId,
            Instant from, Instant to, String keyword, String uploadStatus, Instant updatedBefore) {}

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
        return sessionRepository.findByFiltersOrderByCreatedAtDesc(
                new ShoppingSessionMapper.SessionFilterCriteria(
                        deviceFilter,
                        scopeFilter,
                        criteria.state(),
                        blankToNull(criteria.sessionId()),
                        criteria.userId(),
                        criteria.from(),
                        criteria.to(),
                        blankToNull(criteria.keyword()),
                        criteria.uploadStatus(),
                        criteria.updatedBefore()),
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
                recognitionMs,
                s.getDeviceName()
        );
    }

    private AdminOrderSummaryDto toOrderSummary(
            CabinetOrder o, int lineCount, List<CabinetOrderLine> lines, String splitStatus) {
        String payChannel = resolveOrderPayChannel(o);
        OrderDisplaySnapshot display = resolveOrderDisplaySnapshot(o);
        int coupon = Math.max(0, o.getCouponDiscountCents());
        int member = Math.max(0, o.getMemberDiscountCents());
        int original = o.getOriginalAmountCents() > 0
                ? o.getOriginalAmountCents()
                : o.getTotalAmountCents() + coupon + member;
        String refundPolicy = resolveRefundPolicyName(o.getDeviceId());
        return new AdminOrderSummaryDto(
                o.getOrderId(),
                o.getSessionId(),
                o.getUserId(),
                o.getDeviceId(),
                display.merchantId(),
                o.getTotalAmountCents(),
                original,
                coupon,
                member,
                o.getStatus(),
                payChannel,
                lineCount,
                buildAdminLineSummary(lines),
                resolvePayTradeNo(o),
                o.getPaymentOperationId(),
                o.getRefundedAt(),
                o.isInventoryDeducted(),
                refundPolicy,
                o.getCreatedAt(),
                display.deviceName(),
                display.merchantName(),
                Math.max(0, o.getRefundedCents()),
                resolvePaidAt(o),
                splitStatus
        );
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
            } catch (Exception ignored) {
                // fall through
            }
        }
        return o.getCreatedAt();
    }

    private static String resolveOrderPayChannel(CabinetOrder o) {
        String payChannel = o.getPayChannel();
        if (o.getPaymentOperationId() != null && o.getPaymentOperationId().startsWith("BL-")) {
            return "BALANCE";
        }
        return payChannel;
    }

    private String resolveRefundPolicyName(String deviceId) {
        try {
            return refundPolicyService.resolveForDevice(deviceId).name();
        } catch (Exception ignored) {
            return null;
        }
    }

    private OrderDisplaySnapshot resolveOrderDisplaySnapshot(CabinetOrder o) {
        String merchantId = o.getMerchantId();
        String deviceName = o.getDeviceName();
        String merchantName = o.getMerchantName();
        boolean needLookup = isBlank(merchantId) || isBlank(deviceName) || isBlank(merchantName);
        if (!needLookup || o.getDeviceId() == null) {
            return new OrderDisplaySnapshot(merchantId, deviceName, merchantName);
        }
        return deviceRepository.findById(o.getDeviceId())
                .map(device -> {
                    String mid = isBlank(merchantId) ? device.getMerchantId() : merchantId;
                    String dname = isBlank(deviceName) ? device.getDeviceName() : deviceName;
                    String mname = merchantName;
                    if (isBlank(mname) && mid != null) {
                        mname = merchantRepository.findById(mid)
                                .map(com.aicabinet.trade.domain.Merchant::getMerchantName)
                                .orElse(null);
                    }
                    return new OrderDisplaySnapshot(mid, dname, mname);
                })
                .orElse(new OrderDisplaySnapshot(merchantId, deviceName, merchantName));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record OrderDisplaySnapshot(String merchantId, String deviceName, String merchantName) {}

}
