package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.recognition.RecognitionOutcome;
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

        // CB-045 拍板：比例摊口径——oldSubtotal=当次退款前商品原价合计（递推式比例摊下
        // originalAmountCents 已是上次剩余值，直接取行合计自洽，不依赖该字段历史正确性）
        int oldSubtotal = order.getLines().stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        List<CabinetOrderLine> remaining = SettlementPartialRefundMath.buildRemainingLines(
                SettlementPartialRefundMath.mergeLinesBySku(order.getLines()), partition.refundQtyBySku());
        order.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        orderSupport.applyProportionalRefundPayable(order, oldSubtotal, newSubtotal);
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
        int nextRefunded = prep.priorRefunded() + refundCents;
        int charged = orderPaymentService.chargedCompletedCents(order.getOrderId());
        if (charged > 0) {
            nextRefunded = Math.min(nextRefunded, charged);
        }
        // 支付层正常会累加 refundedCents；演示账号早退 / 历史路径漏写时在此兜底
        if (order.getRefundedCents() < nextRefunded) {
            order.setRefundedCents(nextRefunded);
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
            List<RecognizedItem> restoreItems,
            List<RecognizedItem> keptItems,
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
        List<RecognizedItem> restoreItems = new java.util.ArrayList<>();
        List<RecognizedItem> keptItems = new java.util.ArrayList<>();
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
            var item = new RecognizedItem(sku, req.quantity(), 1f);
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
            inventoryService.restoreForOrder(
                    order.getDeviceId(), partition.restoreItems(), partition.batchBySku(), order.getOrderId());
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
        // 🔴 oldSubtotal 必须在 setLines(remaining) 之前取（=退款前商品原价合计），
        // 否则比例失真 → 预估 ≠ 写侧（2026-10-15 测试拦下的资损点）
        int oldSubtotal = bySku.values().stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        List<CabinetOrderLine> remaining =
                SettlementPartialRefundMath.buildRemainingLines(bySku, refundQtyBySku);
        scratch.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        applyPartialRefundPayablePreview(scratch, oldSubtotal, newSubtotal);
        return Math.max(0, priorPayable - scratch.getTotalAmountCents());
    }

    /**
     * 只读预估：与写侧 {@code applyProportionalRefundPayable} 同一比例摊口径（铁律 51：预估与写侧一致），
     * 但不写券状态——纯计算版（{@code CouponService.proportionalCouponDiscount}）。
     */
    private void applyPartialRefundPayablePreview(CabinetOrder order, int oldSubtotal, int newSubtotal) {
        int safeOld = Math.max(0, oldSubtotal);
        int safeNew = Math.max(0, newSubtotal);
        order.setOriginalAmountCents(safeNew);
        int couponDisc = order.getCouponId() == null ? 0
                : CouponService.proportionalCouponDiscount(
                        Math.max(0, order.getCouponDiscountCents()), safeOld, safeNew);
        order.setCouponDiscountCents(couponDisc);
        int memberDisc = SettlementOrderSupport.scaleDiscountForPartialRefund(
                Math.max(0, order.getMemberDiscountCents()), safeOld, safeNew);
        order.setMemberDiscountCents(memberDisc);
        order.setTotalAmountCents(Math.max(0, safeNew - couponDisc - memberDisc));
    }
}
