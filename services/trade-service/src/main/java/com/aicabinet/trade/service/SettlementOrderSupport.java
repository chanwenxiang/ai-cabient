package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.recognition.RecognitionOutcome;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 结算订单行 / DTO 共享能力（从 {@link SettlementService} 拆出，切断协作类与 Facade 的循环依赖）。
 */
@Service
public class SettlementOrderSupport {

    private final SkuCatalogMapper skuCatalogRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DeviceSlotMapper slotRepository;
    private final MerchantSkuPricingService skuPricingService;
    private final MemberService memberService;
    private final CouponService couponService;
    private final RefundPolicyService refundPolicyService;
    private final RevenueSplitService revenueSplitService;
    private final OrderPaymentService orderPaymentService;
    private final SystemConfigService systemConfigService;
    private final OrderViewAssembler orderViewAssembler;

    public SettlementOrderSupport(SkuCatalogMapper skuCatalogRepository,
                                  CabinetOrderMapper orderRepository,
                                  CabinetOrderLineMapper orderLineRepository,
                                  DeviceSlotMapper slotRepository,
                                  MerchantSkuPricingService skuPricingService,
                                  MemberService memberService,
                                  CouponService couponService,
                                  RefundPolicyService refundPolicyService,
                                  RevenueSplitService revenueSplitService,
                                  OrderPaymentService orderPaymentService,
                                  SystemConfigService systemConfigService,
                                  OrderViewAssembler orderViewAssembler) {
        this.skuCatalogRepository = skuCatalogRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.slotRepository = slotRepository;
        this.skuPricingService = skuPricingService;
        this.memberService = memberService;
        this.couponService = couponService;
        this.refundPolicyService = refundPolicyService;
        this.revenueSplitService = revenueSplitService;
        this.orderPaymentService = orderPaymentService;
        this.systemConfigService = systemConfigService;
        this.orderViewAssembler = orderViewAssembler;
    }

    void applyItemsToOrder(CabinetOrder order, List<RecognizedItem> items) {
        order.getLines().clear();
        Map<String, String> slotBySku = inferSlotBySku(order.getDeviceId());
        long total = 0;
        for (RecognizedItem item : items) {
            SkuCatalog sku = skuCatalogRepository.findById(item.skuId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            ApiMessages.SKU_NOT_FOUND + "：" + item.skuId()));
            // 这里刻意走 2 参重载（每条明细各自取一次策略快照）：本方法只在结算时对**一单的
            // 少量明细**调用，不是商品目录那种 O(N) 热路径；每单额外几次配置查询可忽略，
            // 而少一次重载改动能把改动面收敛在定价服务内部，不动结算链的既有测试桩。
            int unitPrice = skuPricingService.resolveUnitPriceCents(order.getDeviceId(), sku);
            unitPrice = memberService.applyMemberPriceDiscount(order.getUserId(), unitPrice);
            // AU-038：金额一律按 long 算再夹进列域（int 分）。此前 unitPrice * quantity 是
            // int 乘法，上报侧 quantity 无上界 ⇒ 溢出为负的行金额/合计，而 chargeOrder 见负额
            // 直接跳过扣款并标 PAID（等于零元白拿），故必须在写入前拒绝。
            long lineAmount = (long) unitPrice * item.quantity();
            requireCentsInColumnRange("订单行金额（SKU " + item.skuId() + "）", lineAmount);
            total += lineAmount;
            requireCentsInColumnRange("订单合计", total);

            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId(sku.getSkuId());
            line.setSkuName(sku.getSkuName());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(unitPrice);
            line.setLineAmountCents((int) lineAmount);
            line.setUnitCostCents(sku.getPurchaseCostCents());
            line.setConfidence(item.confidence());
            line.setSlotId(slotBySku.get(sku.getSkuId()));
            order.addLine(line);
        }
        order.setOriginalAmountCents((int) total);
        order.setTotalAmountCents((int) total);
    }

    /** 金额列域是 int（分）：越界即已溢出风险，显式 400 拒绝而不是写负额落库（AU-038）。 */
    private static void requireCentsInColumnRange(String what, long cents) {
        if (cents < 0 || cents > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + "超出可记账金额范围（0~" + Integer.MAX_VALUE + " 分），识别结果被拒：" + cents);
        }
    }

    /** 争议改单后按新明细重算折后应付（保留已绑券/会员折扣字段）。 */
    void recalculatePayableAfterLineChange(CabinetOrder order) {
        // AU-038 同族：行数多时 mapToInt().sum() 同样会溢出为负，改用 long 累加后夹列域
        long subtotalLong = order.getLines().stream()
                .mapToLong(CabinetOrderLine::getLineAmountCents)
                .sum();
        requireCentsInColumnRange("订单小计", subtotalLong);
        int subtotal = (int) subtotalLong;
        order.setOriginalAmountCents(subtotal);
        int couponDisc = 0;
        if (order.getCouponId() != null && subtotal > 0) {
            couponDisc = couponService.discountForOrderCoupon(order.getCouponId(), subtotal);
        }
        order.setCouponDiscountCents(couponDisc);
        int memberDisc = Math.min(Math.max(0, order.getMemberDiscountCents()),
                Math.max(0, subtotal - couponDisc));
        order.setMemberDiscountCents(memberDisc);
        order.setTotalAmountCents(Math.max(0, subtotal - couponDisc - memberDisc));
    }

    /**
     * CB-045 拍板（2026-10-15 政府消费券式 + 比例摊）：部分退应付 = 比例摊。
     * 券抵扣与会员折扣都按 oldSubtotal→newSubtotal 比例缩减（每元固定摊销、不重算门槛），
     * 券是否返还由 {@link CouponService#settleAfterPartialRefund} 按全额/部分分流处理。
     * 与争议改单的 {@link #recalculatePayableAfterLineChange}（门槛重算式）刻意分开——两链路口径不同。
     */
    void applyProportionalRefundPayable(CabinetOrder order, int oldSubtotal, int newSubtotal) {
        int safeOld = Math.max(0, oldSubtotal);
        int safeNew = Math.max(0, newSubtotal);
        order.setOriginalAmountCents(safeNew);
        int couponDisc = couponService.settleAfterPartialRefund(order, safeOld, safeNew);
        // 券抵扣字段由本方法自持写入（与 settle 返回值恒等，settle 内部写同值冗余但无害）；
        // 不依赖 settle 的隐藏写侧契约（mock 掉 settle 时字段也必须正确）
        order.setCouponDiscountCents(couponDisc);
        int memberDisc = scaleDiscountForPartialRefund(Math.max(0, order.getMemberDiscountCents()),
                safeOld, safeNew);
        order.setMemberDiscountCents(memberDisc);
        order.setTotalAmountCents(Math.max(0, safeNew - couponDisc - memberDisc));
    }

    /** 比例摊缩放（纯数学）：优惠 × (剩余原价/原价总额)，只减不增。 */
    static int scaleDiscountForPartialRefund(int priorDiscount, int oldSubtotal, int newSubtotal) {
        if (priorDiscount <= 0 || oldSubtotal <= 0 || newSubtotal <= 0) {
            return 0;
        }
        return (int) Math.min(priorDiscount, Math.round((double) priorDiscount * newSubtotal / oldSubtotal));
    }

    /** SKU 唯一绑定某货道时回填货道；同一 SKU 出现在多个货道则不推断。 */
    private Map<String, String> inferSlotBySku(String deviceId) {
        Map<String, String> map = new java.util.HashMap<>();
        if (deviceId == null) {
            return map;
        }
        for (DeviceSlot slot : slotRepository.findByIdDeviceId(deviceId)) {
            if (!slot.isEnabled() || slot.getAssignedSkuId() == null || slot.getAssignedSkuId().isBlank()) {
                continue;
            }
            String existing = map.putIfAbsent(slot.getAssignedSkuId(), slot.getSlotCode());
            if (existing != null) {
                map.put(slot.getAssignedSkuId(), null);
            }
        }
        map.values().removeIf(v -> v == null);
        return map;
    }

    static void applyBatchNos(CabinetOrder order, Map<String, String> batchBySku) {
        if (batchBySku == null || batchBySku.isEmpty()) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            String batch = batchBySku.get(line.getSkuId());
            if (batch != null && !batch.isBlank()) {
                line.setBatchNo(batch);
            }
        }
    }

    /**
     * 仅同包服务在归属校验后调用；勿对 Controller / 跨模块公开，防 IDOR 绕过。
     */
    @Transactional(readOnly = true)
    OrderReadModel getOrderBySession(String sessionId) {
        return orderRepository.findBySessionId(sessionId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
    }

    void replaceOrderLines(CabinetOrder order) {
        orderLineRepository.deleteByOrderId(order.getOrderId());
        if (order.getLines() == null) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            line.setId(null);
            line.setOrderId(order.getOrderId());
            orderLineRepository.save(line);
        }
    }

    void hydrateOrderLines(CabinetOrder order) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        if (order.getLines() != null && !order.getLines().isEmpty()) {
            return;
        }
        order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
    }

    OrderReadModel toDto(CabinetOrder order) {
        hydrateOrderLines(order);
        String refundPolicy = refundPolicyService != null
                ? refundPolicyService.resolveForDevice(order.getDeviceId()).name()
                : null;
        String splitStatus = revenueSplitService.findStatusByOrderId(order.getOrderId()).orElse(null);
        return orderViewAssembler.applyPaymentLedger(
                orderViewAssembler.assembleDetail(
                        order,
                        order.getLines(),
                        splitStatus,
                        resolvePaidAt(order),
                        refundPolicy,
                        null),
                orderPaymentService.chargedCompletedCents(order.getOrderId()),
                orderPaymentService.refundedCompletedCents(order.getOrderId()));
    }

    private Instant resolvePaidAt(CabinetOrder order) {
        String status = order.getStatus();
        if (status == null
                || "PENDING".equals(status)
                || "CANCELLED".equals(status)
                || CabinetConstants.ORDER_STATUS_FAILED.equals(status)) {
            return null;
        }
        String opId = order.getPaymentOperationId();
        if (opId != null && !opId.isBlank()) {
            Instant at = orderPaymentService.findOperationCreatedAt(opId).orElse(null);
            if (at != null) {
                return at;
            }
        }
        return order.getCreatedAt();
    }
}
