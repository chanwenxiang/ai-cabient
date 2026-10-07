package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.util.BizIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 结算落单与扣款（从 {@link SettlementService} 拆出，降低上帝类体积）。
 * toDto / applyItems / applyBatchNos / gravity 等委托 {@link SettlementOrderSupport}。
 */
@Service
public class SettlementOrderFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(SettlementOrderFinalizeService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DeviceValidationService deviceValidationService;
    private final InventoryService inventoryService;
    private final OrderPaymentService orderPaymentService;
    private final UserValidationService userValidationService;
    private final CouponService couponService;
    private final MemberService memberService;
    private final RevenueSplitService revenueSplitService;
    private final NotificationService notificationService;
    private final VideoArchiveService videoArchiveService;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final OpsExceptionService opsExceptionService;
    private final SettlementOrderSupport orderSupport;

    public SettlementOrderFinalizeService(ShoppingSessionMapper sessionRepository,
                                          CabinetOrderMapper orderRepository,
                                          CabinetOrderLineMapper orderLineRepository,
                                          DeviceValidationService deviceValidationService,
                                          InventoryService inventoryService,
                                          OrderPaymentService orderPaymentService,
                                          UserValidationService userValidationService,
                                          CouponService couponService,
                                          MemberService memberService,
                                          RevenueSplitService revenueSplitService,
                                          NotificationService notificationService,
                                          VideoArchiveService videoArchiveService,
                                          DisplaySnapshotHelper displaySnapshotHelper,
                                          SettlementOrderSupport orderSupport,
                                          OpsExceptionService opsExceptionService) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.deviceValidationService = deviceValidationService;
        this.inventoryService = inventoryService;
        this.orderPaymentService = orderPaymentService;
        this.userValidationService = userValidationService;
        this.couponService = couponService;
        this.memberService = memberService;
        this.revenueSplitService = revenueSplitService;
        this.notificationService = notificationService;
        this.videoArchiveService = videoArchiveService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.orderSupport = orderSupport;
        this.opsExceptionService = opsExceptionService;
    }

    OrderReadModel finalizeOrder(ShoppingSession session,
                                 List<VisionServiceClient.RecognizedItem> items) {
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());
        CabinetOrder order = buildOrder(session, items);
        CouponService.BestCoupon appliedCoupon = selectBestCouponForOrder(order);
        boolean unpaid = detectUnpaidBeforeCharge(session, order);
        if (unpaid) {
            appliedCoupon = null;
            clearCouponSelection(order);
        }
        var batchBySku = inventoryService.deductForOrder(
                session.getDeviceId(), items, order.getOrderId(), orderSupport.gravityDeltasForInventory(session));
        SettlementOrderSupport.applyBatchNos(order, batchBySku);
        order.setInventoryDeducted(true);
        order.setStatus(unpaid ? "PENDING" : "PAID");
        // Persist order before charge/coupon mark — payment_operation & user_coupon FK to cabinet_order
        orderRepository.save(order);
        persistOrderLines(order);

        if (unpaid) {
            return finishUnpaidOrder(session, order);
        }
        if (!tryChargeSettledOrder(session, order)) {
            return orderSupport.toDto(order);
        }
        orderRepository.save(order);
        if (appliedCoupon != null) {
            // 审计批次4（P0-5 降级后的健壮性建议）：券核销失败不回滚已成功扣款——
            // 券未核销的后果是「券可再次使用」，比整笔结算失败（货款两空需人工）轻；
            // 留 HIGH 痕迹交运营处理。
            try {
                couponService.markUsed(
                        order.getUserId(),
                        appliedCoupon.couponId(),
                        order.getOrderId(),
                        order.getDeviceId(),
                        order.getCouponDiscountCents()
                );
            } catch (Exception e) {
                log.error("coupon markUsed failed after charge orderId={} couponId={}",
                        order.getOrderId(), appliedCoupon.couponId(), e);
                opsExceptionService.report("COUPON_MARKUSED_FAILED", "HIGH",
                        new OpsExceptionService.ExceptionReport.ExceptionRefs(
                                order.getDeviceId(), order.getSessionId(), order.getOrderId(), order.getUserId()),
                        "券核销失败（扣款已完成）", "orderId=" + order.getOrderId()
                                + " couponId=" + appliedCoupon.couponId() + " err=" + e.getMessage());
            }
        }
        // recordSplit 刻意**不**包 try：分账/钱包入账是资金动作，失败必须随结算事务整体
        // 回滚（扣款一并回滚、结算可重试）——吞掉会留下「已扣款、无分账」的资损黑洞。
        revenueSplitService.recordSplit(order);
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        try {
            memberService.onOrderPaid(session.getUserId(), order.getTotalAmountCents(), order.getOrderId());
        } catch (Exception e) {
            log.warn("member stats update failed order={}", order.getOrderId(), e);
        }
        try {
            notificationService.notifyConsumer(
                    session.getUserId(),
                    "order_paid",
                    Map.of("orderId", order.getOrderId(), "amount", yuan(order.getTotalAmountCents())),
                    "ORDER",
                    order.getOrderId());
        } catch (Exception e) {
            log.warn("order paid notification failed order={}", order.getOrderId(), e);
        }
        videoArchiveService.archiveAfterSettlement(session);
        log.info("settled session={} order={} amount={} couponDiscount={} channel={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents(),
                order.getCouponDiscountCents(), order.getPayChannel());
        return orderSupport.toDto(order);
    }

    /** 余额不足时预判为待支付（优惠券延后核销）。 */
    private boolean detectUnpaidBeforeCharge(ShoppingSession session, CabinetOrder order) {
        if (userValidationService.canChargeViaPasswordFree(session.getUserId(), session.getEntryChannel())) {
            return false;
        }
        try {
            int hold;
            if (ConsumerPreauthService.STATUS_FROZEN.equalsIgnoreCase(
                    session.getPreauthStatus() == null ? "" : session.getPreauthStatus())) {
                hold = Math.max(0, session.getPreauthCents());
            } else {
                hold = 0;
            }
            userValidationService.validateSufficientBalanceForCharge(
                    session.getUserId(), order.getTotalAmountCents(), hold);
            return false;
        } catch (BalanceInsufficientException e) {
            return true;
        }
    }

    private OrderReadModel finishUnpaidOrder(ShoppingSession session, CabinetOrder order) {
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        videoArchiveService.archiveAfterSettlement(session);
        log.info("unpaid order created session={} order={} amount={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
        return orderSupport.toDto(order);
    }

    /**
     * 尝试扣款；余额不足时转 PENDING 并返回 false（订单已落库）。
     * 其他支付异常继续抛出。
     */
    private boolean tryChargeSettledOrder(ShoppingSession session, CabinetOrder order) {
        try {
            orderPaymentService.chargeOrder(order);
            return true;
        } catch (BalanceInsufficientException e) {
            // F1-B：预授权冲抵已保留、差额转待支付——事务未被毒化（chargeOrder noRollbackFor），
            // 「capture 保留 + PENDING」按设计意图达成（不再 500+假争议）
            return finishUnpaidOrderAfterCharge(session, order, e.getMessage());
        } catch (ResponseStatusException e) {
            if (!isInsufficientBalance(e)) {
                throw e;
            }
            // 扣款失败转待支付：同样不占券，补扣时再选
            clearCouponSelection(order);
            order.setStatus("PENDING");
            orderRepository.save(order);
            session.setOrderId(order.getOrderId());
            sessionRepository.save(session);
            videoArchiveService.archiveAfterSettlement(session);
            log.info("unpaid order after charge fail session={} order={} amount={}",
                    session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
            return false;
        }
    }

    /** F1-B：chargeOrder 信号化不足后的 PENDING 收尾（与 catch 路径共用）。 */
    private boolean finishUnpaidOrderAfterCharge(ShoppingSession session, CabinetOrder order, String reason) {
        clearCouponSelection(order);
        order.setStatus("PENDING");
        orderRepository.save(order);
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        videoArchiveService.archiveAfterSettlement(session);
        log.info("unpaid order after charge fail session={} order={} amount={} reason={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents(), reason);
        return false;
    }

    private static String yuan(int cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private static boolean isInsufficientBalance(ResponseStatusException e) {
        if (e.getStatusCode() != HttpStatus.PRECONDITION_FAILED) {
            return false;
        }
        String reason = e.getReason();
        return reason != null && (reason.contains("余额") || reason.toLowerCase().contains("insufficient"));
    }

    /** Pick preferred or best coupon and rewrite payable; caller must markUsed after order is persisted (FK). */
    private CouponService.BestCoupon selectBestCouponForOrder(CabinetOrder order) {
        int subtotal = order.getTotalAmountCents();
        order.setOriginalAmountCents(subtotal);
        if (subtotal <= 0 || order.getUserId() == null) {
            return null;
        }
        Long preferred = null;
        if (order.getSessionId() != null) {
            preferred = sessionRepository.findById(order.getSessionId())
                    .map(ShoppingSession::getPreferredCouponId)
                    .orElse(null);
        }
        // 🔴 V319：必须传 deviceId —— 不传则范围判定会被跳过（deviceId=null ⇒ 不限制），
        //    「券限某商户/某柜机」就永远不会生效。
        var best = couponService.selectPreferredOrBest(
                order.getUserId(), preferred, subtotal, order.getDeviceId());
        if (best.isEmpty()) {
            return null;
        }
        var pick = best.get();
        int discount = Math.min(pick.discountCents(), subtotal);
        order.setCouponId(pick.couponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        log.info("applied coupon order={} couponId={} preferred={} discount={} payable={}",
                order.getOrderId(), pick.couponId(), preferred, discount, order.getTotalAmountCents());
        return pick;
    }

    /** 待支付不占券：还原应付为折前金额并清空券字段。 */
    private static void clearCouponSelection(CabinetOrder order) {
        int original = order.getOriginalAmountCents();
        if (original <= 0) {
            original = order.getTotalAmountCents() + Math.max(0, order.getCouponDiscountCents());
        }
        order.setCouponId(null);
        order.setCouponDiscountCents(0);
        order.setTotalAmountCents(Math.max(0, original));
        order.setOriginalAmountCents(Math.max(0, original));
    }

    private CabinetOrder buildOrder(ShoppingSession session,
                                    List<VisionServiceClient.RecognizedItem> items) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(BizIds.nextNumeric());
        order.setSessionId(session.getSessionId());
        order.setUserId(session.getUserId());
        order.setDeviceId(session.getDeviceId());
        displaySnapshotHelper.stampOrderSnapshot(order);
        order.setStatus("PAID");
        orderSupport.applyItemsToOrder(order, items);
        return order;
    }

    private void persistOrderLines(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            line.setOrderId(order.getOrderId());
            orderLineRepository.save(line);
        }
    }
}
