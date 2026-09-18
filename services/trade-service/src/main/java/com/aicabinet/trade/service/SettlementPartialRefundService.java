package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 按行部分退款（从 {@link SettlementService} 拆出，降低上帝类体积）。
 * 行改/应付重算委托 {@link SettlementOrderSupport}；纯数学见 {@link SettlementPartialRefundMath}。
 */
@Service
public class SettlementPartialRefundService {

    private static final Logger log = LoggerFactory.getLogger(SettlementPartialRefundService.class);

    private final CabinetOrderMapper orderRepository;
    private final OrderPaymentService orderPaymentService;
    private final InventoryService inventoryService;
    private final RevenueSplitService revenueSplitService;
    private final CouponService couponService;
    private final SettlementOrderSupport orderSupport;
    private final SettlementPartialRefundService self;

    public SettlementPartialRefundService(CabinetOrderMapper orderRepository,
                                          OrderPaymentService orderPaymentService,
                                          InventoryService inventoryService,
                                          RevenueSplitService revenueSplitService,
                                          CouponService couponService,
                                          SettlementOrderSupport orderSupport,
                                          @Lazy SettlementPartialRefundService self) {
        this.orderRepository = orderRepository;
        this.orderPaymentService = orderPaymentService;
        this.inventoryService = inventoryService;
        this.revenueSplitService = revenueSplitService;
        this.couponService = couponService;
        this.orderSupport = orderSupport;
        this.self = self;
    }

    /**
     * 按行部分退款（竞品口径）：指定 SKU/数量退款；行级或默认决定是否回库。
     * 退完全部行 → {@code REFUNDED}；否则 → {@code PARTIAL_REFUNDED}。
     * C04 顺序：纯读计算退款计划 → 渠道退款（幂等 idemKey=订单+金额+原因）→ prepare+finalize 落库。
     * 渠道失败时本地零改动；渠道成功但落库失败时，重试用相同金额/原因命中同一幂等键，安全重试。
     */
    public PartialRefundResult partialRefund(CabinetOrder order,
                                             List<OrderRefundRequest.PartialRefundLine> refundLines,
                                             boolean defaultRestore,
                                             String reason) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定要退款的商品行");
        }
        int refundCents = self.planPartialRefundCents(order.getOrderId(), refundLines);
        orderPaymentService.refundOrder(order, refundCents,
                reason == null ? "按行部分退款" : reason);
        PartialRefundPrep prep = self.preparePartialRefund(order.getOrderId(), refundLines, defaultRestore);
        return self.finalizePartialRefund(prep);
    }

    /**
     * C04：只读规划——校验退款行并计算退款金额，不写订单/库存/券。
     * 与 {@link #estimatePartialRefundCents} 同一套口径；调用方持有订单支付分布式锁期间数据稳定。
     */
    @Transactional(readOnly = true)
    public int planPartialRefundCents(String orderId,
                                      List<OrderRefundRequest.PartialRefundLine> refundLines) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已全额退款");
        }
        int refundCents = estimatePartialRefundCents(order, refundLines);
        if (refundCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额为 0");
        }
        return refundCents;
    }

    public record PartialRefundPrep(
            CabinetOrder order,
            int refundCents,
            int priorRefunded,
            boolean remainingEmpty,
            boolean anyRestored) {}

    @Transactional
    public PartialRefundPrep preparePartialRefund(String orderId,
                                                  List<OrderRefundRequest.PartialRefundLine> refundLines,
                                                  boolean defaultRestore) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        orderSupport.hydrateOrderLines(order);
        if (order.getLines() == null || order.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单无商品行，无法按行退款");
        }
        int priorPayable = Math.max(0, order.getTotalAmountCents());
        PartialRefundPartition partition = validateAndPartitionRefundLines(order, refundLines, defaultRestore);

        List<CabinetOrderLine> remaining = SettlementPartialRefundMath.buildRemainingLines(
                SettlementPartialRefundMath.mergeLinesBySku(order.getLines()), partition.refundQtyBySku());
        order.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        couponService.recalcOrRestoreAfterPartialRefund(order, newSubtotal);
        orderSupport.recalculatePayableAfterLineChange(order);
        int refundCents = Math.max(0, priorPayable - order.getTotalAmountCents());
        if (refundCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额为 0");
        }

        applyPartialRefundInventory(order, partition, remaining.isEmpty());
        int priorRefunded = Math.max(0, order.getRefundedCents());
        orderRepository.save(order);
        orderSupport.replaceOrderLines(order);
        return new PartialRefundPrep(order, refundCents, priorRefunded, remaining.isEmpty(), partition.anyRestored());
    }

    @Transactional
    public PartialRefundResult finalizePartialRefund(PartialRefundPrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        orderSupport.hydrateOrderLines(order);
        int refundCents = prep.refundCents();
        // 支付层正常会累加 refundedCents；演示账号早退 / 历史路径漏写时在此兜底
        if (order.getRefundedCents() < prep.priorRefunded() + refundCents) {
            order.setRefundedCents(prep.priorRefunded() + refundCents);
        }
        if (order.getRefundedAt() == null) {
            order.setRefundedAt(java.time.Instant.now());
        }
        boolean full = prep.remainingEmpty() || order.getTotalAmountCents() <= 0;
        order.setStatus(full ? CabinetConstants.ORDER_STATUS_REFUNDED : "PARTIAL_REFUNDED");
        if (full) {
            order.setRefundedAt(java.time.Instant.now());
        }
        revenueSplitService.adjustSplitAfterPartialRefund(order, full);
        orderRepository.save(order);
        orderSupport.replaceOrderLines(order);
        log.info("partial refund order={} refundCents={} status={} anyRestored={}",
                order.getOrderId(), refundCents, order.getStatus(), prep.anyRestored());
        return new PartialRefundResult(refundCents, order.getStatus(), prep.anyRestored());
    }

    private record PartialRefundPartition(
            List<VisionServiceClient.RecognizedItem> restoreItems,
            List<VisionServiceClient.RecognizedItem> keptItems,
            Map<String, String> batchBySku,
            Map<String, Integer> refundQtyBySku,
            boolean anyRestored) {
    }

    private PartialRefundPartition validateAndPartitionRefundLines(
            CabinetOrder order,
            List<OrderRefundRequest.PartialRefundLine> refundLines,
            boolean defaultRestore) {
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(order.getLines());
        Map<String, Integer> refundQtyBySku =
                SettlementPartialRefundMath.validatePartialRefundQuantities(bySku, refundLines);
        return buildPartialRefundPartition(bySku, refundLines, defaultRestore, refundQtyBySku);
    }

    private static PartialRefundPartition buildPartialRefundPartition(
            Map<String, CabinetOrderLine> bySku,
            List<OrderRefundRequest.PartialRefundLine> refundLines,
            boolean defaultRestore,
            Map<String, Integer> refundQtyBySku) {
        List<VisionServiceClient.RecognizedItem> restoreItems = new java.util.ArrayList<>();
        List<VisionServiceClient.RecognizedItem> keptItems = new java.util.ArrayList<>();
        Map<String, String> batchBySku = new java.util.HashMap<>();
        boolean anyRestored = false;
        for (OrderRefundRequest.PartialRefundLine req : refundLines) {
            if (req == null || req.skuId() == null || req.skuId().isBlank()) {
                continue;
            }
            String sku = req.skuId().trim();
            CabinetOrderLine line = bySku.get(sku);
            boolean restore = req.restoreInventory() != null ? req.restoreInventory() : defaultRestore;
            if (line.getBatchNo() != null && !line.getBatchNo().isBlank()) {
                batchBySku.putIfAbsent(sku, line.getBatchNo());
            }
            var item = new VisionServiceClient.RecognizedItem(sku, req.quantity(), 1f);
            if (restore) {
                restoreItems.add(item);
                anyRestored = true;
            } else {
                keptItems.add(item);
            }
        }
        return new PartialRefundPartition(restoreItems, keptItems, batchBySku, refundQtyBySku, anyRestored);
    }

    private void applyPartialRefundInventory(CabinetOrder order, PartialRefundPartition partition,
                                             boolean remainingEmpty) {
        if (!order.isInventoryDeducted()) {
            return;
        }
        if (!partition.restoreItems().isEmpty()) {
            inventoryService.restoreForOrder(order.getDeviceId(), partition.restoreItems(), partition.batchBySku());
        }
        if (!partition.keptItems().isEmpty()) {
            inventoryService.recordRefundKeptGoods(
                    order.getDeviceId(), partition.keptItems(), partition.batchBySku(), order.getOrderId());
        }
        if (remainingEmpty) {
            order.setInventoryDeducted(false);
        }
    }

    public record PartialRefundResult(int refundedCents, String status, boolean anyInventoryRestored) {}

    @Transactional(readOnly = true)
    public int estimatePartialRefundCents(CabinetOrder order,
                                          List<OrderRefundRequest.PartialRefundLine> refundLines) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            return 0;
        }
        CabinetOrder scratch = SettlementPartialRefundMath.copyOrderForPartialRefundEstimate(order);
        orderSupport.hydrateOrderLines(scratch);
        if (scratch.getLines() == null || scratch.getLines().isEmpty()) {
            return 0;
        }
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(scratch.getLines());
        Map<String, Integer> refundQtyBySku =
                SettlementPartialRefundMath.validatePartialRefundQuantities(bySku, refundLines);
        int priorPayable = Math.max(0, scratch.getTotalAmountCents());
        List<CabinetOrderLine> remaining =
                SettlementPartialRefundMath.buildRemainingLines(bySku, refundQtyBySku);
        scratch.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        applyPartialRefundPayablePreview(scratch, newSubtotal);
        orderSupport.recalculatePayableAfterLineChange(scratch);
        return Math.max(0, priorPayable - scratch.getTotalAmountCents());
    }

    /** 只读预估：按剩余明细重算应付，不写券状态。 */
    private void applyPartialRefundPayablePreview(CabinetOrder order, int remainingSubtotalCents) {
        order.setOriginalAmountCents(Math.max(0, remainingSubtotalCents));
        if (order.getCouponId() == null) {
            order.setCouponDiscountCents(0);
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        int discount = couponService.discountForOrderCoupon(order.getCouponId(), remainingSubtotalCents);
        if (discount <= 0) {
            order.setCouponId(null);
            order.setCouponDiscountCents(0);
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, remainingSubtotalCents - discount));
    }
}
