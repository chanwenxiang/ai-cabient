package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CancelUnpaidOrderRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.UnpaidOrderActionResultDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UnpaidOrderService {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderService.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final long REMIND_COOLDOWN_MINUTES = 30;

    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final UserInfoMapper userInfoRepository;
    private final AdminAuditLogMapper auditLogRepository;
    private final InventoryService inventoryService;
    private final OrderPaymentService orderPaymentService;
    private final RevenueSplitService revenueSplitService;
    private final MemberService memberService;
    private final CouponService couponService;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final RiskControlService riskControlService;
    private final SystemConfigService systemConfigService;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final SettlementService settlementService;
    private final ConsumerPreauthService consumerPreauthService;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;

    public UnpaidOrderService(CabinetOrderMapper orderRepository,
                              CabinetOrderLineMapper orderLineRepository,
                              UserInfoMapper userInfoRepository,
                              AdminAuditLogMapper auditLogRepository,
                              InventoryService inventoryService,
                              OrderPaymentService orderPaymentService,
                              RevenueSplitService revenueSplitService,
                              MemberService memberService,
                              CouponService couponService,
                              MerchantScopeService merchantScopeService,
                              PermissionService permissionService,
                              AdminAuditService auditService,
                              RiskControlService riskControlService,
                              SystemConfigService systemConfigService,
                              WeChatMiniAppClient weChatMiniAppClient,
                              WeChatMiniAppProperties weChatMiniAppProperties,
                              @Lazy SettlementService settlementService,
                              ConsumerPreauthService consumerPreauthService,
                              NotificationService notificationService,
                              DistributedLockService distributedLockService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.userInfoRepository = userInfoRepository;
        this.auditLogRepository = auditLogRepository;
        this.inventoryService = inventoryService;
        this.orderPaymentService = orderPaymentService;
        this.revenueSplitService = revenueSplitService;
        this.memberService = memberService;
        this.couponService = couponService;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.riskControlService = riskControlService;
        this.systemConfigService = systemConfigService;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.settlementService = settlementService;
        this.consumerPreauthService = consumerPreauthService;
        this.notificationService = notificationService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public UnpaidOrderActionResultDto remind(Long operatorId, String orderId) {
        permissionService.requirePermission(operatorId, "ops:order:remind");
        CabinetOrder order = requirePendingScoped(operatorId, orderId);
        if (recentlyReminded(orderId)) {
            return new UnpaidOrderActionResultDto(orderId, order.getStatus(),
                    "30 分钟内已催付，请稍后再试", false, false);
        }
        UserInfo user = userInfoRepository.findById(order.getUserId()).orElse(null);
        boolean sent = false;
        String message;
        if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
            message = "用户未绑定微信，已记催付审计（请线下触达）";
        } else {
            String templateId = weChatMiniAppProperties.subscribeTemplateId();
            if (templateId == null || templateId.isBlank()) {
                templateId = "mock-unpaid-template";
            }
            String amount = String.format("¥%.2f", order.getTotalAmountCents() / 100.0);
            sent = weChatMiniAppClient.sendSubscribeMessage(
                    user.getWxOpenId(),
                    templateId,
                    "pages/orders/orders",
                    Map.of(
                            "thing1", "待支付订单提醒",
                            "amount2", amount,
                            "time3", TIME_FMT.format(Instant.now())
                    ));
            message = sent ? "催付订阅消息已发送" : "催付发送失败（请检查小程序订阅配置），已记审计";
        }
        auditService.record(operatorId, "ORDER_REMIND", "ORDER", orderId, message);
        return new UnpaidOrderActionResultDto(orderId, order.getStatus(), message, sent, false);
    }

    @Transactional
    public UnpaidOrderActionResultDto cancel(Long operatorId, String orderId, CancelUnpaidOrderRequest request) {
        permissionService.requirePermission(operatorId, "ops:order:cancel");
        return runWithOrderPaymentLock(orderId, () -> {
            CabinetOrder order = requirePendingScoped(operatorId, orderId);
            String reason = request.reason().trim();
            restoreInventory(order);
            order.setStatus("CANCELLED");
            orderRepository.save(order);
            consumerPreauthService.releaseBySessionId(order.getSessionId());

            boolean blacklist = Boolean.TRUE.equals(request.blacklist());
            if (blacklist && order.getUserId() != null) {
                Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
                riskControlService.addBlacklist(operatorId, order.getUserId(),
                        "待支付关单：" + reason, expires);
            }
            auditService.record(operatorId, "ORDER_CANCEL_UNPAID", "ORDER", orderId,
                    reason + (blacklist ? "；已拉黑用户 30 天" : ""));
            log.info("unpaid order cancelled order={} by={} blacklist={}", orderId, operatorId, blacklist);
            return new UnpaidOrderActionResultDto(orderId, "CANCELLED",
                    blacklist ? "已关单并拉黑用户 30 天" : "待支付订单已关闭，库存已回滚", false, blacklist);
        });
    }

    @Transactional
    public OrderDto collect(Long operatorId, String orderId) {
        permissionService.requireAnyPermission(operatorId, "ops:order:remind", "ops:order:cancel", "ops:order:refund");
        return runWithOrderPaymentLock(orderId, () -> {
            CabinetOrder order = requirePendingScoped(operatorId, orderId);
            markPaid(order);
            auditService.record(operatorId, "ORDER_COLLECT_UNPAID", "ORDER", orderId, "运营代收");
            return settlementService.getOrderBySession(order.getSessionId());
        });
    }

    @Transactional
    public OrderDto collectByUser(Long userId, String orderId) {
        return runWithOrderPaymentLock(orderId, () -> {
            CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
            if (!order.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
            }
            if (!"PENDING".equals(order.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
            }
            markPaid(order);
            return settlementService.getOrderBySession(order.getSessionId());
        });
    }

    @Transactional
    public int autoCancelExpired() {
        int hours = systemConfigService.getInt(SystemConfigService.UNPAID_AUTO_CANCEL_HOURS, 48);
        if (hours <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<CabinetOrder> expired = orderRepository.findByStatusAndCreatedAtBefore("PENDING", cutoff, 500);
        boolean autoBlacklist = systemConfigService.getBoolean(SystemConfigService.UNPAID_AUTO_BLACKLIST, false);
        int n = 0;
        for (CabinetOrder order : expired) {
            try {
                CabinetOrder cancelled = runWithOrderPaymentLock(order.getOrderId(), () -> {
                    CabinetOrder locked = orderRepository.findByIdForUpdate(order.getOrderId()).orElse(null);
                    if (locked == null || !"PENDING".equals(locked.getStatus())) {
                        return null;
                    }
                    restoreInventory(locked);
                    locked.setStatus("CANCELLED");
                    orderRepository.save(locked);
                    consumerPreauthService.releaseBySessionId(locked.getSessionId());
                    if (autoBlacklist && locked.getUserId() != null) {
                        riskControlService.addBlacklist(0L, locked.getUserId(),
                                "待支付超时自动关单", Instant.now().plus(7, ChronoUnit.DAYS));
                    }
                    auditService.record(0L, "ORDER_AUTO_CANCEL_UNPAID", "ORDER", locked.getOrderId(),
                            "超时 " + hours + " 小时自动关单；是否拉黑=" + (autoBlacklist ? "是" : "否"));
                    return locked;
                });
                if (cancelled != null) {
                    n++;
                }
            } catch (Exception ex) {
                log.warn("auto cancel unpaid failed order={}", order.getOrderId(), ex);
            }
        }
        if (n > 0) {
            log.info("auto cancelled unpaid orders count={} hours={}", n, hours);
        }
        return n;
    }

    private void markPaid(CabinetOrder order) {
        hydrate(order);
        // 创建 PENDING 时未占券；补扣时再选最优券后扣款并核销
        CouponService.BestCoupon applied = applyBestCouponForCollect(order);
        orderPaymentService.chargeOrder(order);
        order.setStatus("PAID");
        orderRepository.save(order);
        if (applied != null) {
            try {
                couponService.markUsed(
                        order.getUserId(),
                        applied.couponId(),
                        order.getOrderId(),
                        order.getDeviceId(),
                        order.getCouponDiscountCents());
            } catch (Exception ex) {
                log.warn("coupon markUsed on collect failed order={}", order.getOrderId(), ex);
            }
        }
        revenueSplitService.recordSplit(order);
        try {
            memberService.onOrderPaid(order.getUserId(), order.getTotalAmountCents(), order.getOrderId());
        } catch (Exception ex) {
            log.warn("member stats on collect failed order={}", order.getOrderId(), ex);
        }
        try {
            notificationService.notifyConsumer(
                    order.getUserId(),
                    "order_paid",
                    Map.of("orderId", order.getOrderId(), "amount", yuan(order.getTotalAmountCents())),
                    "ORDER",
                    order.getOrderId());
        } catch (Exception ex) {
            log.warn("order paid notification on collect failed order={}", order.getOrderId(), ex);
        }
    }

    private static String yuan(int cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private CouponService.BestCoupon applyBestCouponForCollect(CabinetOrder order) {
        if (order.getUserId() == null || order.getTotalAmountCents() <= 0) {
            return null;
        }
        if (order.getCouponId() != null && order.getCouponDiscountCents() > 0) {
            // 兼容旧数据：PENDING 上已带券字段则沿用
            return new CouponService.BestCoupon(
                    order.getCouponId(), order.getCouponDiscountCents(), null);
        }
        int subtotal = order.getOriginalAmountCents() > 0
                ? order.getOriginalAmountCents()
                : order.getTotalAmountCents();
        order.setOriginalAmountCents(subtotal);
        order.setTotalAmountCents(subtotal);
        var best = couponService.selectBestCoupon(order.getUserId(), subtotal);
        if (best.isEmpty()) {
            return null;
        }
        var pick = best.get();
        int discount = Math.min(pick.discountCents(), subtotal);
        order.setCouponId(pick.couponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        return pick;
    }

    private void restoreInventory(CabinetOrder order) {
        hydrate(order);
        if (!order.isInventoryDeducted() || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        List<VisionServiceClient.RecognizedItem> items = order.getLines().stream()
                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(), 1f))
                .toList();
        Map<String, String> batchBySku = order.getLines().stream()
                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                .collect(Collectors.toMap(CabinetOrderLine::getSkuId, CabinetOrderLine::getBatchNo, (a, b) -> a));
        inventoryService.restoreForOrder(order.getDeviceId(), items, batchBySku);
        order.setInventoryDeducted(false);
    }

    private CabinetOrder requirePendingScoped(Long operatorId, String orderId) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, order.getDeviceId());
        if (!"PENDING".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        return order;
    }

    private void hydrate(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
    }

    private boolean recentlyReminded(String orderId) {
        Instant since = Instant.now().minus(REMIND_COOLDOWN_MINUTES, ChronoUnit.MINUTES);
        List<AdminAuditLog> logs = auditLogRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc("ORDER", orderId);
        for (int i = logs.size() - 1; i >= 0; i--) {
            AdminAuditLog row = logs.get(i);
            if ("ORDER_REMIND".equals(row.getAction())
                    && row.getCreatedAt() != null
                    && row.getCreatedAt().isAfter(since)) {
                return true;
            }
        }
        return false;
    }

    private <T> T runWithOrderPaymentLock(String orderId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(OrderPaymentService.orderPaymentLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(OrderPaymentService.orderPaymentLockKey(orderId));
        }
    }
}
