package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PurchaseSuggestionDto;
import com.aicabinet.trade.config.RopProperties;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.PurchaseOrderLineMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能采购建议（以销定购）。
 *
 * <p>基于全部设备的近 7/14 日销量计算日均需求，叠加采购前置期与目标覆盖天数，
 * 再扣除仓库现有库存与待收采购量，得出建议采购量。只对近 14 日有动销的商品
 * 给出建议，避免把无动销商品回补成死库存。</p>
 */
@Service
public class PurchaseSuggestionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_COVERAGE_DAYS = 14;

    private final PermissionService permissionService;
    private final CabinetOrderLineMapper orderLineRepository;
    private final WarehouseInventoryMapper warehouseInventoryRepository;
    private final PurchaseOrderLineMapper purchaseOrderLineRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final SkuDelistReviewMapper skuReviewRepository;
    private final RopProperties ropProperties;

    public PurchaseSuggestionService(PermissionService permissionService,
                                     CabinetOrderLineMapper orderLineRepository,
                                     WarehouseInventoryMapper warehouseInventoryRepository,
                                     PurchaseOrderLineMapper purchaseOrderLineRepository,
                                     SkuCatalogMapper skuCatalogRepository,
                                     SkuDelistReviewMapper skuReviewRepository,
                                     RopProperties ropProperties) {
        this.permissionService = permissionService;
        this.orderLineRepository = orderLineRepository;
        this.warehouseInventoryRepository = warehouseInventoryRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.skuReviewRepository = skuReviewRepository;
        this.ropProperties = ropProperties;
    }

    @Transactional(readOnly = true)
    public List<PurchaseSuggestionDto> suggest(Long operatorId, String warehouseId,
                                               int leadTimeDays, int coverageDays) {
        permissionService.requirePermission(operatorId, "ops:procurement:list");

        String wh = warehouseId == null || warehouseId.isBlank() ? null : warehouseId.trim();
        int lead = leadTimeDays > 0 ? leadTimeDays : ropProperties.leadTimeDays();
        int cover = coverageDays > 0 ? coverageDays : DEFAULT_COVERAGE_DAYS;
        int horizon = lead + cover;

        Instant since7 = LocalDate.now(ZONE).minusDays(7).atStartOfDay(ZONE).toInstant();
        Instant since14 = LocalDate.now(ZONE).minusDays(14).atStartOfDay(ZONE).toInstant();

        Map<String, Integer> sold7 = toMap(orderLineRepository.sumSoldQtyAllSince(since7));
        Map<String, Integer> sold14 = toMap(orderLineRepository.sumSoldQtyAllSince(since14));
        Map<String, Integer> onHand = toMap(warehouseInventoryRepository.sumQtyBySku(wh));
        Map<String, Integer> pending = toMap(purchaseOrderLineRepository.pendingQtyBySku(wh));

        Map<String, String> skuNames = new HashMap<>();
        skuCatalogRepository.findAllByOrderBySkuIdAsc()
                .forEach(sku -> skuNames.put(sku.getSkuId(), sku.getSkuName()));

        Set<String> skuIds = new LinkedHashSet<>(sold14.keySet());
        skuIds.addAll(sold7.keySet());

        // 选品诊断联动：建议下架/已下架的商品不再纳入采购建议
        Set<String> excludedByReview = skuReviewRepository.findAll().stream()
                .filter(r -> "RECOMMEND_DELIST".equals(r.getReviewStatus())
                        || "DELISTED".equals(r.getReviewStatus()))
                .map(r -> r.getSkuId())
                .collect(java.util.stream.Collectors.toSet());

        List<PurchaseSuggestionDto> out = new ArrayList<>();
        for (String skuId : skuIds) {
            if (excludedByReview.contains(skuId)) {
                continue;
            }
            int q7 = sold7.getOrDefault(skuId, 0);
            int q14 = sold14.getOrDefault(skuId, 0);
            if (q14 <= 0) {
                continue;
            }
            double avgDaily = q7 > 0 ? q7 / 7.0 : q14 / 14.0;
            int demand = (int) Math.ceil(avgDaily * horizon);
            int suggest = Math.max(0,
                    demand - onHand.getOrDefault(skuId, 0) - pending.getOrDefault(skuId, 0));
            if (suggest <= 0) {
                continue;
            }
            out.add(new PurchaseSuggestionDto(
                    skuId,
                    skuNames.getOrDefault(skuId, skuId),
                    onHand.getOrDefault(skuId, 0),
                    pending.getOrDefault(skuId, 0),
                    q7,
                    q14,
                    Math.round(avgDaily * 100.0) / 100.0,
                    horizon,
                    suggest,
                    "SALES_DRIVEN"));
        }

        out.sort(Comparator.comparingInt(PurchaseSuggestionDto::suggestQty).reversed()
                .thenComparing(Comparator.comparingDouble(PurchaseSuggestionDto::avgDailySales).reversed()));
        return out;
    }

    private static Map<String, Integer> toMap(List<Object[]> rows) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }
}
