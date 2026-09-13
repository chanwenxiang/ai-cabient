package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
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

    String gravityDeltasForInventory(ShoppingSession session) {
        return systemConfigService.usesGravityFusion() ? session.getGravityDeltas() : null;
    }

    void applyItemsToOrder(CabinetOrder order, List<VisionServiceClient.RecognizedItem> items) {
        order.getLines().clear();
        Map<String, String> slotBySku = inferSlotBySku(order.getDeviceId());
        int total = 0;
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuCatalog sku = skuCatalogRepository.findById(item.skuId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            ApiMessages.SKU_NOT_FOUND + "：" + item.skuId()));
            int unitPrice = skuPricingService.resolveUnitPriceCents(order.getDeviceId(), sku);
            unitPrice = memberService.applyMemberPriceDiscount(order.getUserId(), unitPrice);
            int lineAmount = unitPrice * item.quantity();
            total += lineAmount;

            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId(sku.getSkuId());
            line.setSkuName(sku.getSkuName());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(unitPrice);
            line.setLineAmountCents(lineAmount);
            line.setUnitCostCents(sku.getPurchaseCostCents());
            line.setConfidence(item.confidence());
            line.setSlotId(slotBySku.get(sku.getSkuId()));
            order.addLine(line);
        }
        order.setOriginalAmountCents(total);
        order.setTotalAmountCents(total);
    }

    /** 争议改单后按新明细重算折后应付（保留已绑券/会员折扣字段）。 */
    void recalculatePayableAfterLineChange(CabinetOrder order) {
        int subtotal = order.getLines().stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
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
        return orderViewAssembler.assembleDetail(
                order,
                order.getLines(),
                splitStatus,
                resolvePaidAt(order),
                refundPolicy,
                null);
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
