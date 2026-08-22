package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SkuDelistReviewDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuDelistReview;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选品淘汰闭环：SKU 动销诊断（销量/营收/库存天数）→ 运营评审（淘汰/保留/替换）→ 下架动作。
 */
@Service
public class SkuDelistReviewService {

    private static final Logger log = LoggerFactory.getLogger(SkuDelistReviewService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CabinetOrderLineMapper lineRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final SkuDelistReviewMapper reviewRepository;
    private final InventoryLotService inventoryLotService;

    public SkuDelistReviewService(CabinetOrderLineMapper lineRepository,
                                  DeviceSkuInventoryMapper inventoryRepository,
                                  SkuCatalogMapper skuCatalogRepository,
                                  SkuDelistReviewMapper reviewRepository,
                                  InventoryLotService inventoryLotService) {
        this.lineRepository = lineRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.reviewRepository = reviewRepository;
        this.inventoryLotService = inventoryLotService;
    }

    /** 基于近 N 天订单生成/刷新全量 SKU 评审行。 */
    @Transactional
    public List<SkuDelistReviewDto> runReview(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        Map<String, long[]> sales = new HashMap<>(); // skuId -> [qty, revenueCents]
        for (Object[] row : lineRepository.skuBreakdownSince(since)) {
            if (row == null || row.length < 4 || row[0] == null) {
                continue;
            }
            String skuId = String.valueOf(row[0]);
            long qty = num(row[2]);
            long revenue = num(row[3]);
            sales.merge(skuId, new long[]{qty, revenue}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }

        Map<String, Long> stock = new HashMap<>();
        Map<String, Boolean> ledgerByDevice = new HashMap<>();
        Map<String, Map<String, Integer>> sellableByDevice = new HashMap<>();
        for (DeviceSkuInventory inv : inventoryRepository.findAllLimit(5000)) {
            if (inv == null || inv.getSkuId() == null) {
                continue;
            }
            String deviceId = inv.getDeviceId();
            boolean ledger = ledgerByDevice.computeIfAbsent(deviceId, inventoryLotService::deviceUsesLotLedger);
            int qty = ledger
                    ? sellableByDevice.computeIfAbsent(deviceId, inventoryLotService::sellableQtyBySku)
                            .getOrDefault(inv.getSkuId(), 0)
                    : inv.getQuantity();
            stock.merge(inv.getSkuId(), (long) qty, Long::sum);
        }

        Map<String, SkuCatalog> skus = skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .collect(LinkedHashMap::new, (m, s) -> m.put(s.getSkuId(), s), LinkedHashMap::putAll);

        for (SkuCatalog sku : skus.values()) {
            if (sku == null || sku.getSkuId() == null || sku.getSkuId().isBlank()) {
                continue;
            }
            long[] s = sales.getOrDefault(sku.getSkuId(), new long[]{0L, 0L});
            long qty = s[0];
            long revenue = s[1];
            long curStock = stock.getOrDefault(sku.getSkuId(), 0L);
            double avgDaily = window <= 0 ? 0d : (double) qty / window;
            // 勿写 ?: 混用 int / Integer：无销量有库存时第二支为 null，外层会按 int 拆箱 NPE（BUG-011）
            Integer stockDays;
            if (avgDaily > 0) {
                stockDays = (int) Math.round(curStock / avgDaily);
            } else if (curStock > 0) {
                stockDays = null;
            } else {
                stockDays = 0;
            }
            String level = performanceLevel(qty, avgDaily);

            SkuDelistReview review = reviewRepository.findBySkuId(sku.getSkuId())
                    .orElseGet(() -> {
                        SkuDelistReview r = new SkuDelistReview();
                        r.setSkuId(sku.getSkuId());
                        r.setReviewStatus("PENDING");
                        r.setCreatedAt(Instant.now());
                        return r;
                    });
            review.setPerformanceLevel(level);
            review.setSalesQty((int) Math.min(qty, Integer.MAX_VALUE));
            review.setRevenueCents(revenue);
            review.setStockDays(stockDays);
            review.setUpdatedAt(Instant.now());
            if (review.getReviewStatus() == null || review.getReviewStatus().isBlank()) {
                review.setReviewStatus("PENDING");
            }
            // 勿用 BaseTradeMapper.save：新建时 id=null 会回落到 skuId 当 PK，引发异常（BUG-011）
            if (review.getId() == null) {
                reviewRepository.insert(review);
            } else {
                reviewRepository.updateById(review);
            }
        }
        log.info("sku review refreshed window={}d skus={}", window, skus.size());
        return list();
    }

    @Transactional(readOnly = true)
    public List<SkuDelistReviewDto> list() {
        Map<String, SkuCatalog> skus = skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .collect(LinkedHashMap::new, (m, s) -> m.put(s.getSkuId(), s), LinkedHashMap::putAll);
        return reviewRepository.findAll().stream()
                .map(r -> toDto(r, skus.get(r.getSkuId()), skus))
                .toList();
    }

    /**
     * 评审动作：RECOMMEND_DELIST 建议下架 / DELIST 确认下架（改商品状态）/ KEEP 保留。
     */
    @Transactional
    public SkuDelistReviewDto decide(String skuId, String action, String reason, String replaceSkuId, Long operatorId) {
        SkuDelistReview review = reviewRepository.findBySkuId(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该商品暂无诊断记录"));
        String act = action == null ? "" : action.trim().toUpperCase();
        if (!List.of("RECOMMEND_DELIST", "DELIST", "KEEP").contains(act)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的评审动作");
        }
        review.setActionType(act);
        review.setReason(reason);
        review.setReplaceSkuId(replaceSkuId);
        review.setReviewedBy(operatorId);
        review.setReviewedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        if ("DELIST".equals(act)) {
            review.setReviewStatus("DELISTED");
            SkuCatalog sku = skuCatalogRepository.findById(skuId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
            sku.setStatus("INACTIVE");
            skuCatalogRepository.save(sku);
        } else if ("KEEP".equals(act)) {
            review.setReviewStatus("KEPT");
        } else {
            review.setReviewStatus("RECOMMEND_DELIST");
        }
        reviewRepository.save(review);

        Map<String, SkuCatalog> skus = skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .collect(LinkedHashMap::new, (m, s) -> m.put(s.getSkuId(), s), LinkedHashMap::putAll);
        return toDto(review, skus.get(skuId), skus);
    }

    private static String performanceLevel(long qty, double avgDaily) {
        if (qty <= 0) {
            return "NO_SALES";
        }
        if (avgDaily >= 1.0) {
            return "BEST_SELLER";
        }
        if (avgDaily >= 0.2) {
            return "NORMAL";
        }
        return "SLOW_MOVER";
    }

    private SkuDelistReviewDto toDto(SkuDelistReview r, SkuCatalog sku, Map<String, SkuCatalog> skus) {
        String skuName = sku != null ? sku.getSkuName() : r.getSkuId();
        String category = sku != null ? sku.getCategory() : null;
        String replaceName = r.getReplaceSkuId() == null ? null
                : (skus.get(r.getReplaceSkuId()) != null ? skus.get(r.getReplaceSkuId()).getSkuName() : r.getReplaceSkuId());
        return new SkuDelistReviewDto(
                r.getId(),
                r.getSkuId(),
                skuName,
                category,
                r.getReviewStatus(),
                r.getPerformanceLevel(),
                r.getSalesQty() == null ? 0 : r.getSalesQty(),
                r.getRevenueCents() == null ? 0L : r.getRevenueCents(),
                r.getStockDays(),
                r.getActionType(),
                r.getReason(),
                r.getReplaceSkuId(),
                replaceName,
                r.getReviewedBy(),
                r.getReviewedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private static long num(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return new BigDecimal(String.valueOf(v)).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
