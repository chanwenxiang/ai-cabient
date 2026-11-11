package com.aicabinet.trade.service.view;

import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单读模型唯一装配点：渠道归一、原价回退、行摘要等派生逻辑只在此处计算。
 */
@Component
public class OrderViewAssembler {

    /** 商品图来源（sku_catalog.image_url）；无参构造时为 null（既有单测不需要库）。 */
    private final SkuCatalogMapper skuCatalogMapper;

    public OrderViewAssembler() {
        this(null);
    }

    @Autowired
    public OrderViewAssembler(SkuCatalogMapper skuCatalogMapper) {
        this.skuCatalogMapper = skuCatalogMapper;
    }

    /**
     * @param lines           订单行；可空
     * @param splitStatus     分账状态；可空
     * @param paidAt          支付完成时间；可空（调用方解析后传入）
     * @param refundPolicy    柜机退款策略名；可空
     * @param payTradeNo      覆盖支付流水号；null 则用订单字段
     * @param includeLines    是否输出明细行（详情 true，列表 false）
     */
    public OrderReadModel assemble(CabinetOrder order,
                                   List<CabinetOrderLine> lines,
                                   String splitStatus,
                                   Instant paidAt,
                                   String refundPolicy,
                                   String payTradeNo,
                                   boolean includeLines) {
        return assemble(order, lines, splitStatus, paidAt, refundPolicy, payTradeNo, includeLines, null);
    }

    /**
     * R14-F2：列表页跨单批量取图的重载——调用方把整页的 skuId→imageUrl 映射预先算好传进来，
     * 避免 assembler 逐单调 loadSkuImages 产生 N 次 sku_catalog 点查（每页 +N 次的 N+1）。
     * 传 null 走原有路径（单单取，详情页/非列表场景不受影响）。
     */
    public OrderReadModel assemble(CabinetOrder order,
                                   List<CabinetOrderLine> lines,
                                   String splitStatus,
                                   Instant paidAt,
                                   String refundPolicy,
                                   String payTradeNo,
                                   boolean includeLines,
                                   java.util.Map<String, String> preloadedImages) {
        if (order == null) {
            throw new IllegalArgumentException("order required");
        }
        List<CabinetOrderLine> safeLines = lines == null ? List.of() : lines;
        int coupon = Math.max(0, order.getCouponDiscountCents());
        int member = Math.max(0, order.getMemberDiscountCents());
        int activity = Math.max(0, order.getActivityDiscountCents());
        int original = originalAmountCents(order, coupon, member);
        String channel = normalizePayChannel(order);
        String tradeNo = (payTradeNo != null && !payTradeNo.isBlank())
                ? payTradeNo
                : blankToNull(order.getPayTradeNo());
        Map<String, String> images = preloadedImages != null
                ? preloadedImages
                : loadSkuImages(safeLines);
        List<OrderLineDto> lineDtos = includeLines ? toLineDtos(safeLines, images) : null;
        return new OrderReadModel(
                order.getOrderId(),
                order.getSessionId(),
                order.getUserId(),
                order.getDeviceId(),
                blankToNull(order.getMerchantId()),
                blankToNull(order.getDeviceName()),
                blankToNull(order.getMerchantName()),
                order.getTotalAmountCents(),
                original,
                coupon,
                member,
                activity,
                Math.max(0, order.getGrossAmountCents()),
                order.getStatus(),
                channel,
                itemQty(safeLines),
                buildLineSummary(safeLines),
                buildLineSkuIds(safeLines),
                coverImageUrl(safeLines, images),
                tradeNo,
                blankToNull(order.getPaymentOperationId()),
                order.getRefundedAt(),
                Math.max(0, order.getRefundedCents()),
                order.isInventoryDeducted(),
                blankToNull(refundPolicy),
                order.getCreatedAt(),
                paidAt,
                blankToNull(splitStatus),
                lineDtos,
                includeLines ? order.getBalanceBeforeCents() : null,
                includeLines ? order.getBalanceAfterCents() : null
        );
    }

    public OrderReadModel assembleSummary(CabinetOrder order,
                                          List<CabinetOrderLine> lines,
                                          String splitStatus,
                                          Instant paidAt,
                                          String refundPolicy,
                                          String payTradeNo) {
        return assemble(order, lines, splitStatus, paidAt, refundPolicy, payTradeNo, false);
    }

    public OrderReadModel assembleDetail(CabinetOrder order,
                                         List<CabinetOrderLine> lines,
                                         String splitStatus,
                                         Instant paidAt,
                                         String refundPolicy,
                                         String payTradeNo) {
        return assemble(order, lines, splitStatus, paidAt, refundPolicy, payTradeNo, true);
    }

    /** 详情金额以支付流水覆盖订单表可能被写坏的 total/refunded。 */
    public OrderReadModel applyPaymentLedger(OrderReadModel model, int chargedCents, int refundedCents) {
        return OrderPaymentLedger.apply(model, chargedCents, refundedCents);
    }

    /** 余额账本扣款以 BL- 操作号为准，与三端历史口径一致。 */
    public static String normalizePayChannel(CabinetOrder order) {
        String channel = order.getPayChannel();
        if (order.getPaymentOperationId() != null && order.getPaymentOperationId().startsWith("BL-")) {
            channel = "BALANCE";
        }
        if (channel == null || channel.isBlank()) {
            return "UNKNOWN";
        }
        return channel;
    }

    public static int originalAmountCents(CabinetOrder order, int coupon, int member) {
        if (order.getOriginalAmountCents() > 0) {
            return order.getOriginalAmountCents();
        }
        // MA-029 后会员/活动折扣都在行金额里；把三档优惠加回才是「全部优惠前原价」。
        // 漏掉 activityDiscountCents 会让活动单的原价少一个活动优惠额（P3）。
        return order.getTotalAmountCents() + coupon + member
                + Math.max(0, order.getActivityDiscountCents());
    }

    public static int itemQty(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        return lines.stream().mapToInt(l -> Math.max(0, l.getQuantity())).sum();
    }

    /**
     * 商品摘要：名称 x数量 ·货道 ·批次；超过 2 行追加「等N件」。
     * 三端统一口径（原 admin「等N种」已收敛到此）。
     */
    public static String buildLineSummary(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String preview = lines.stream()
                .limit(2)
                .map(l -> {
                    String sku = l.getSkuName() == null || l.getSkuName().isBlank()
                            ? l.getSkuId()
                            : l.getSkuName();
                    String name = sku + " x" + l.getQuantity();
                    if (l.getSlotId() != null && !l.getSlotId().isBlank()) {
                        name += " ·货道" + l.getSlotId().trim();
                    }
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo().trim();
                    }
                    return name;
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (lines.size() > 2) {
            return preview + " 等" + lines.size() + "件";
        }
        return preview;
    }

    /** 商品行 SKU ID 串（`、` 分隔，与 {@link #buildLineSummary} 同序同源；空行返回 null）。 */
    public static String buildLineSkuIds(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return lines.stream()
                .map(l -> l.getSkuId() == null ? "" : l.getSkuId().trim())
                .reduce((a, b) -> a.isEmpty() ? b : a + "、" + b)
                .orElse(null);
    }

    /**
     * 批量取商品图（sku_catalog.image_url，相对路径由前端 absoluteImageUrl 补域名）。
     * 无 mapper（单测直接 new）或查询失败一律回空表——订单读面不能因配图缺失而失败。
     */
    private Map<String, String> loadSkuImages(List<CabinetOrderLine> lines) {
        if (skuCatalogMapper == null || lines == null || lines.isEmpty()) {
            return Map.of();
        }
        List<String> ids = lines.stream()
                .map(CabinetOrderLine::getSkuId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> images = new HashMap<>();
        try {
            for (SkuCatalog sku : skuCatalogMapper.selectBatchIds(ids)) {
                if (sku != null && sku.getSkuId() != null && sku.getImageUrl() != null
                        && !sku.getImageUrl().isBlank()) {
                    images.put(sku.getSkuId().trim(), sku.getImageUrl().trim());
                }
            }
        } catch (RuntimeException e) {
            return Map.of();
        }
        return images;
    }

    /** 列表封面图：第一条有图的商品行，与「商品」列同序。 */
    private static String coverImageUrl(List<CabinetOrderLine> lines, Map<String, String> images) {
        for (CabinetOrderLine line : lines) {
            String url = imageFor(images, line.getSkuId());
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String imageFor(Map<String, String> images, String skuId) {
        if (images.isEmpty() || skuId == null) {
            return null;
        }
        String url = images.get(skuId.trim());
        return url == null || url.isEmpty() ? null : url;
    }

    private static List<OrderLineDto> toLineDtos(List<CabinetOrderLine> lines, Map<String, String> images) {
        return lines.stream()
                .map(l -> new OrderLineDto(
                        l.getSkuId(), l.getSkuName(), l.getQuantity(),
                        l.getUnitPriceCents(), l.getLineAmountCents(), l.getBatchNo(), l.getSlotId(),
                        imageFor(images, l.getSkuId())))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
