package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算按行退款纯计算：从 {@link SettlementService} 拆出，降低上帝类体积与回归面。
 */
public final class SettlementPartialRefundMath {

    private SettlementPartialRefundMath() {}

    public static CabinetOrder copyOrderForPartialRefundEstimate(CabinetOrder order) {
        CabinetOrder copy = new CabinetOrder();
        copy.setOrderId(order.getOrderId());
        copy.setTotalAmountCents(order.getTotalAmountCents());
        copy.setOriginalAmountCents(order.getOriginalAmountCents());
        copy.setCouponId(order.getCouponId());
        copy.setCouponDiscountCents(order.getCouponDiscountCents());
        copy.setMemberDiscountCents(order.getMemberDiscountCents());
        if (order.getLines() != null) {
            List<CabinetOrderLine> lines = new ArrayList<>();
            for (CabinetOrderLine line : order.getLines()) {
                CabinetOrderLine l = new CabinetOrderLine();
                l.setSkuId(line.getSkuId());
                l.setSkuName(line.getSkuName());
                l.setQuantity(line.getQuantity());
                l.setUnitPriceCents(line.getUnitPriceCents());
                l.setLineAmountCents(line.getLineAmountCents());
                l.setBatchNo(line.getBatchNo());
                l.setSlotId(line.getSlotId());
                l.setUnitCostCents(line.getUnitCostCents());
                l.setConfidence(line.getConfidence());
                lines.add(l);
            }
            copy.setLines(lines);
        }
        return copy;
    }

    public static Map<String, CabinetOrderLine> mergeLinesBySku(List<CabinetOrderLine> lines) {
        Map<String, CabinetOrderLine> bySku = new LinkedHashMap<>();
        for (CabinetOrderLine line : lines) {
            if (line.getSkuId() == null) {
                continue;
            }
            bySku.merge(line.getSkuId(), line, (a, b) -> {
                a.setQuantity(a.getQuantity() + b.getQuantity());
                a.setLineAmountCents(a.getLineAmountCents() + b.getLineAmountCents());
                return a;
            });
        }
        return bySku;
    }

    public static Map<String, Integer> validatePartialRefundQuantities(
            Map<String, CabinetOrderLine> bySku,
            List<OrderRefundRequest.PartialRefundLine> refundLines) {
        Map<String, Integer> refundQtyBySku = new LinkedHashMap<>();
        for (OrderRefundRequest.PartialRefundLine req : refundLines) {
            if (req == null || req.skuId() == null || req.skuId().isBlank() || req.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款行 SKU/数量无效");
            }
            String sku = req.skuId().trim();
            CabinetOrderLine line = bySku.get(sku);
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单不含商品：" + sku);
            }
            int already = refundQtyBySku.getOrDefault(sku, 0);
            int need = already + req.quantity();
            if (need > line.getQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "退款数量超过订单行：" + sku + " 可退 " + (line.getQuantity() - already));
            }
            refundQtyBySku.put(sku, need);
        }
        return refundQtyBySku;
    }

    public static List<CabinetOrderLine> buildRemainingLines(
            Map<String, CabinetOrderLine> bySku,
            Map<String, Integer> refundQtyBySku) {
        List<CabinetOrderLine> remaining = new ArrayList<>();
        for (Map.Entry<String, CabinetOrderLine> e : bySku.entrySet()) {
            CabinetOrderLine src = e.getValue();
            int cut = refundQtyBySku.getOrDefault(e.getKey(), 0);
            int left = src.getQuantity() - cut;
            if (left > 0) {
                CabinetOrderLine copy = new CabinetOrderLine();
                copy.setSkuId(src.getSkuId());
                copy.setSkuName(src.getSkuName());
                copy.setQuantity(left);
                copy.setUnitPriceCents(src.getUnitPriceCents());
                copy.setLineAmountCents(src.getUnitPriceCents() * left);
                copy.setConfidence(src.getConfidence());
                copy.setBatchNo(src.getBatchNo());
                copy.setSlotId(src.getSlotId());
                copy.setUnitCostCents(src.getUnitCostCents());
                remaining.add(copy);
            }
        }
        return remaining;
    }
}
