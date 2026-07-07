package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.repository.*;
import com.aicabinet.trade.support.ApiMessages;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminDashboardService {

    private static final int EXPORT_LIMIT = 5000;
    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD
    );

    private final DeviceInfoRepository deviceRepository;
    private final ShoppingSessionRepository sessionRepository;
    private final CabinetOrderRepository orderRepository;
    private final DisputeTicketRepository disputeRepository;
    private final SettlementService settlementService;
    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final SkuCatalogRepository skuCatalogRepository;
    private final AdminAuditService auditService;
    private final AdminAuditLogRepository auditLogRepository;
    private final PermissionService permissionService;
    private final PaymentService paymentService;
    private final RechargeOrderRepository rechargeOrderRepository;

    public AdminDashboardService(DeviceInfoRepository deviceRepository,
                                 ShoppingSessionRepository sessionRepository,
                                 CabinetOrderRepository orderRepository,
                                 DisputeTicketRepository disputeRepository,
                                 SettlementService settlementService,
                                 UserInfoRepository userInfoRepository,
                                 UserAccountRepository userAccountRepository,
                                 SkuCatalogRepository skuCatalogRepository,
                                 AdminAuditService auditService,
                                 AdminAuditLogRepository auditLogRepository,
                                 PermissionService permissionService,
                                 PaymentService paymentService,
                                 RechargeOrderRepository rechargeOrderRepository) {
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
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
    }

    public AdminStatsDto stats(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        long deviceTotal = deviceRepository.count();
        long deviceOnline = deviceRepository.findAll().stream()
                .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .count();

        return new AdminStatsDto(
                deviceTotal,
                deviceOnline,
                sessionRepository.countByStateIn(ACTIVE_STATES),
                sessionRepository.countByCreatedAtAfter(todayStart),
                orderRepository.countByCreatedAtAfter(todayStart),
                orderRepository.sumTotalAmountSince(todayStart),
                orderRepository.count(),
                orderRepository.sumTotalAmount(),
                disputeRepository.countByStatus("OPEN")
        );
    }

    public List<AdminDeviceDto> listDevices(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        Map<String, ShoppingSession> activeByDevice = sessionRepository
                .findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .collect(Collectors.toMap(
                        ShoppingSession::getDeviceId,
                        s -> s,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b
                ));

        return deviceRepository.findAll().stream()
                .map(d -> toDeviceDto(d, activeByDevice.get(d.getDeviceId())))
                .toList();
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, int page, int size,
                                                      String deviceId, SessionState state) {
        permissionService.requirePermission(operatorId, "ops:session:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ShoppingSession> result = querySessions(deviceId, state, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSessionDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, int page, int size, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CabinetOrder> result = (deviceId == null || deviceId.isBlank())
                ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                : orderRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId.trim(), pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toOrderSummary).toList(),
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
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional
    public AdminSessionDto cancelSession(Long operatorId, String sessionId) {
        permissionService.requirePermission(operatorId, "ops:session:cancel");
        ShoppingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
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

    public List<AdminDeviceReportDto> deviceReports(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .collect(Collectors.toMap(ShoppingSession::getDeviceId, s -> s, (a, b) -> a));

        return deviceRepository.findAll().stream()
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
        permissionService.requireAnyPermission(operatorId, "ops:audit:list", "ops:dashboard:view");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<com.aicabinet.trade.domain.AdminAuditLog> result =
                auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return toAuditPage(result);
    }

    public List<AdminAuditLogDto> listRecentAuditLogs(Long operatorId, int size, boolean mineOnly) {
        permissionService.requireAnyPermission(operatorId, "ops:audit:recent", "ops:audit:list", "ops:dashboard:view");
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

    public PageResult<AdminUserDto> listUsers(Long operatorId, int page, int size, String phone) {
        permissionService.requirePermission(operatorId, "ops:user:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<UserInfo> result = (phone == null || phone.isBlank())
                ? userInfoRepository.findAllByOrderByUserIdDesc(pageable)
                : userInfoRepository.findByPhoneNumberContainingOrderByUserIdDesc(phone.trim(), pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toUserDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sku:list");
        return skuCatalogRepository.findAll().stream()
                .map(s -> new SkuCatalogDto(s.getSkuId(), s.getSkuName(), s.getPriceCents()))
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
        sku.setSkuName(request.skuName().trim());
        sku.setPriceCents(request.priceCents());
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_CREATE", "SKU", sku.getSkuId(),
                sku.getSkuName() + " price=" + sku.getPriceCents());
        return new SkuCatalogDto(sku.getSkuId(), sku.getSkuName(), sku.getPriceCents());
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        sku.setSkuName(request.skuName().trim());
        sku.setPriceCents(request.priceCents());
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_UPDATE", "SKU", sku.getSkuId(),
                sku.getSkuName() + " price=" + sku.getPriceCents());
        return new SkuCatalogDto(sku.getSkuId(), sku.getSkuName(), sku.getPriceCents());
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
        deviceRepository.save(device);
        auditService.record(operatorId, "DEVICE_CREATE", "DEVICE", deviceId, device.getDeviceName());
        return toDeviceDto(device, null);
    }

    @Transactional
    public AdminDeviceDto updateDevice(Long operatorId, String deviceId, UpdateDeviceRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            device.setDeviceName(request.deviceName().trim());
        }
        if (request.deviceType() != null && !request.deviceType().isBlank()) {
            device.setDeviceType(request.deviceType().trim());
        }
        deviceRepository.save(device);
        auditService.record(operatorId, "DEVICE_UPDATE", "DEVICE", deviceId, device.getDeviceName());
        ShoppingSession active = findActiveSession(deviceId);
        return toDeviceDto(device, active);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:order:list");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CabinetOrder> page = (deviceId == null || deviceId.isBlank())
                ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                : orderRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId.trim(), pageable);
        StringBuilder sb = new StringBuilder("orderId,sessionId,userId,deviceId,totalAmountCents,status,lineCount,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            sb.append(csv(o.getOrderId())).append(',')
                    .append(csv(o.getSessionId())).append(',')
                    .append(o.getUserId()).append(',')
                    .append(csv(o.getDeviceId())).append(',')
                    .append(o.getTotalAmountCents()).append(',')
                    .append(csv(o.getStatus())).append(',')
                    .append(o.getLines().size()).append(',')
                    .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        permissionService.requirePermission(operatorId, "ops:session:list");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ShoppingSession> page = querySessions(deviceId, state, pageable);
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
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(6);
        Instant since = start.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            buckets.put(start.plusDays(i), new long[]{0, 0});
        }
        for (CabinetOrder order : orderRepository.findByCreatedAtAfter(since)) {
            LocalDate day = order.getCreatedAt().atZone(zone).toLocalDate();
            long[] bucket = buckets.get(day);
            if (bucket != null) {
                bucket[0]++;
                bucket[1] += order.getTotalAmountCents();
            }
        }
        List<AdminDailyStatDto> days = buckets.entrySet().stream()
                .map(e -> new AdminDailyStatDto(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1]))
                .toList();
        return new AdminTrendDto(days);
    }

    @Transactional
    public AdminUserDto adjustBalance(Long operatorId, Long userId, AdjustBalanceRequest request) {
        permissionService.requirePermission(operatorId, "ops:user:balance");
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_ADJUST_OPERATOR_BALANCE);
        }
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        long next = (long) account.getBalanceCents() + request.deltaCents();
        if (next < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.BALANCE_NEGATIVE);
        }
        if (next > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.BALANCE_TOO_LARGE);
        }
        account.setBalanceCents((int) next);
        userAccountRepository.save(account);
        auditService.record(operatorId, "BALANCE_ADJUST", "USER", String.valueOf(userId),
                "delta=" + request.deltaCents() + " balance=" + next);
        return toUserDto(user);
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listRecharges(Long operatorId, int page, int size,
                                                      String status, Long userId) {
        permissionService.requirePermission(operatorId, "ops:order:list");
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
        permissionService.requirePermission(operatorId, "ops:user:balance");
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

    private Page<ShoppingSession> querySessions(String deviceId, SessionState state, Pageable pageable) {
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        String dev = hasDevice ? deviceId.trim() : null;
        if (hasDevice && state != null) {
            return sessionRepository.findByDeviceIdAndStateOrderByCreatedAtDesc(dev, state, pageable);
        }
        if (hasDevice) {
            return sessionRepository.findByDeviceIdOrderByCreatedAtDesc(dev, pageable);
        }
        if (state != null) {
            return sessionRepository.findByStateOrderByCreatedAtDesc(state, pageable);
        }
        return sessionRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private AdminDeviceDto toDeviceDto(DeviceInfo d, ShoppingSession active) {
        return new AdminDeviceDto(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getDeviceType(),
                d.getOnlineStatus(),
                active != null ? active.getSessionId() : null,
                active != null ? active.getState().name() : null,
                d.getUpdatedAt()
        );
    }

    private AdminSessionDto toSessionDto(ShoppingSession s) {
        return new AdminSessionDto(
                s.getSessionId(), s.getUserId(), s.getDeviceId(), s.getState(),
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(), s.getVideoUri(),
                s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    private AdminOrderSummaryDto toOrderSummary(CabinetOrder o) {
        return new AdminOrderSummaryDto(
                o.getOrderId(), o.getSessionId(), o.getUserId(), o.getDeviceId(),
                o.getTotalAmountCents(), o.getStatus(), o.getLines().size(), o.getCreatedAt()
        );
    }
}
