package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PurchaseSuggestionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.RopProperties;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.PurchaseOrderLineMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
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
 * <p>基于全部设备的近 7/14 日销量计算日均需求；当启用趋势模型且近期销量呈明显
 * 上升/下降趋势时，用近 N 日线性回归斜率预测未来覆盖期日均销量，避免按历史均值
 * 采购导致趋势品类缺货或衰退品类积压。叠加采购前置期与目标覆盖天数，再扣除仓库
 * 现有库存与待收采购量得出建议采购量。只对近 14 日有动销的商品给出建议。</p>
 */
@Service
public class PurchaseSuggestionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_COVERAGE_DAYS = 14;
    private static final int RECENT_LEVEL_DAYS = 7;
    /** 趋势 lift（斜率 × 覆盖期）绝对值小于该阈值时视为无趋势，退回销量驱动。 */
    private static final double TREND_LIFT_MIN = 0.5;
    /** 趋势对覆盖期的累计贡献上限（相对近期日均水平），防止斜率噪声导致建议量失控。 */
    private static final double TREND_LIFT_CAP_RATIO = 0.8;

    private final PermissionService permissionService;
    private final CabinetOrderLineMapper orderLineRepository;
    private final WarehouseInventoryMapper warehouseInventoryRepository;
    private final PurchaseOrderLineMapper purchaseOrderLineRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final SkuDelistReviewMapper skuReviewRepository;
    private final RopProperties ropProperties;
    private final PurchaseSuggestionService self;

    public PurchaseSuggestionService(PermissionService permissionService,
                                     CabinetOrderLineMapper orderLineRepository,
                                     WarehouseInventoryMapper warehouseInventoryRepository,
                                     PurchaseOrderLineMapper purchaseOrderLineRepository,
                                     SkuCatalogMapper skuCatalogRepository,
                                     SkuDelistReviewMapper skuReviewRepository,
                                     RopProperties ropProperties,
                                     @Lazy PurchaseSuggestionService self) {
        this.permissionService = permissionService;
        this.orderLineRepository = orderLineRepository;
        this.warehouseInventoryRepository = warehouseInventoryRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.skuReviewRepository = skuReviewRepository;
        this.ropProperties = ropProperties;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<PurchaseSuggestionDto> suggest(Long operatorId, String warehouseId,
                                               int leadTimeDays, int coverageDays) {
        permissionService.requirePermission(operatorId, "ops:procurement:list");

        String wh = warehouseId == null || warehouseId.isBlank() ? null : warehouseId.trim();
        int lead = leadTimeDays > 0 ? leadTimeDays : ropProperties.leadTimeDays();
        int cover = coverageDays > 0 ? coverageDays : DEFAULT_COVERAGE_DAYS;
        int horizon = lead + cover;
        LocalDate today = LocalDate.now(ZONE);

        Instant since7 = LocalDate.now(ZONE).minusDays(7).atStartOfDay(ZONE).toInstant();
        Instant since14 = LocalDate.now(ZONE).minusDays(14).atStartOfDay(ZONE).toInstant();
        int window = Math.max(RECENT_LEVEL_DAYS, ropProperties.forecastWindowDays());
        Instant sinceWindow = today.minusDays(window - 1L).atStartOfDay(ZONE).toInstant();

        Map<String, Integer> sold7 = toMap(orderLineRepository.sumSoldQtyAllSince(since7));
        Map<String, Integer> sold14 = toMap(orderLineRepository.sumSoldQtyAllSince(since14));
        Map<String, Map<LocalDate, Integer>> daily = bucketDaily(
                orderLineRepository.soldQtyDailySince(sinceWindow));
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
            DemandModel model = demandModel(daily.get(skuId), q7, q14, today, window, horizon, lead);
            int demand = model.demand();
            int safety = model.safetyStock();
            int suggest = Math.max(0,
                    demand + safety - onHand.getOrDefault(skuId, 0) - pending.getOrDefault(skuId, 0));
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
                    model.avgDaily(),
                    horizon,
                    suggest,
                    safety,
                    model.forecastDaily(),
                    model.trendPerDay(),
                    model.reason()));
        }

        out.sort(Comparator.comparingInt(PurchaseSuggestionDto::suggestQty).reversed()
                .thenComparing(Comparator.comparingDouble(PurchaseSuggestionDto::avgDailySales).reversed()));
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseSuggestionDto> suggestPage(
            Long operatorId, String warehouseId, int leadTimeDays, int coverageDays, int page, int size) {
        List<PurchaseSuggestionDto> all = self.suggest(operatorId, warehouseId, leadTimeDays, coverageDays);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        int from = p * s;
        if (from >= all.size()) {
            return new PageResult<>(List.of(), p, s, all.size());
        }
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), p, s, all.size());
    }

    /** 需求模型：日均销量 / 覆盖期需求 / 安全库存 / 预测日均 / 日趋势 / 建议理由。 */
    private record DemandModel(double avgDaily, int demand, int safetyStock, double forecastDaily,
                               double trendPerDay, String reason) {
    }

    private DemandModel demandModel(Map<LocalDate, Integer> daily, int q7, int q14,
                                    LocalDate today, int windowDays, int horizon, int leadTimeDays) {
        double avgDaily = round2(q7 > 0 ? q7 / 7.0 : q14 / 14.0);
        double forecastDaily = avgDaily;
        double trendPerDay = 0.0;
        String reason = "SALES_DRIVEN";
        double[] series = null;

        if (ropProperties.trendForecastEnabled() && daily != null && !daily.isEmpty()) {
            series = toSeries(daily, today, windowDays);
            int lookback = Math.min(ropProperties.trendLookbackDays(), series.length);
            if (lookback >= RECENT_LEVEL_DAYS) {
                double level = meanOfLast(series, RECENT_LEVEL_DAYS);
                double slope = linearSlope(series, lookback);
                if (level > 0) {
                    double lift = slope * horizon;
                    if (Math.abs(lift) >= TREND_LIFT_MIN) {
                        double cap = TREND_LIFT_CAP_RATIO * level * horizon;
                        double cappedLift = Math.max(-cap, Math.min(cap, lift));
                        forecastDaily = Math.max(0.05 * level, level + cappedLift / horizon);
                        trendPerDay = round2(slope);
                        forecastDaily = round2(forecastDaily);
                        reason = "TREND_FORECAST";
                    }
                }
            }
        }

        int demand = (int) Math.ceil(forecastDaily * horizon);
        int safety = safetyStock(series, leadTimeDays);
        return new DemandModel(avgDaily, demand, safety, forecastDaily, trendPerDay, reason);
    }

    /** 动态安全库存 = z(服务水平) × 日销量标准差 × √采购前置期（经典 (R,S) 库存模型）。 */
    private int safetyStock(double[] series, int leadTimeDays) {
        if (series == null || series.length < 2 || leadTimeDays <= 0) {
            return 0;
        }
        int n = Math.min(ropProperties.trendLookbackDays(), series.length);
        double sigma = stddevOfLast(series, n);
        double z = serviceLevelZ(ropProperties.safetyServiceLevel());
        return (int) Math.ceil(z * sigma * Math.sqrt(leadTimeDays));
    }

    private static double stddevOfLast(double[] series, int days) {
        int n = Math.min(days, series.length);
        if (n < 2) {
            return 0;
        }
        double mean = meanOfLast(series, n);
        double sumSq = 0;
        for (int i = series.length - n; i < series.length; i++) {
            double d = series[i] - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    /** 服务水平 → 标准正态 z 值（常用档位线性插值，0.8~0.999 之外取边界值）。 */
    private static double serviceLevelZ(double level) {
        double[][] table = {
                {0.80, 0.842}, {0.85, 1.036}, {0.90, 1.282}, {0.95, 1.645},
                {0.97, 1.881}, {0.98, 2.054}, {0.99, 2.326}, {0.995, 2.576}, {0.999, 3.090}
        };
        if (level <= table[0][0]) {
            return table[0][1];
        }
        for (int i = 1; i < table.length; i++) {
            if (level <= table[i][0]) {
                double x0 = table[i - 1][0], y0 = table[i - 1][1];
                double x1 = table[i][0], y1 = table[i][1];
                return y0 + (level - x0) / (x1 - x0) * (y1 - y0);
            }
        }
        return table[table.length - 1][1];
    }

    /** 将「SKU + 日」销量补齐为近 windowDays 天的连续序列（缺失日补 0）。 */
    private static double[] toSeries(Map<LocalDate, Integer> daily, LocalDate today, int windowDays) {
        double[] series = new double[windowDays];
        for (int i = 0; i < windowDays; i++) {
            LocalDate day = today.minusDays(windowDays - 1L - i);
            series[i] = daily.getOrDefault(day, 0);
        }
        return series;
    }

    private static double meanOfLast(double[] series, int days) {
        double sum = 0;
        int n = Math.min(days, series.length);
        for (int i = series.length - n; i < series.length; i++) {
            sum += series[i];
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 近 lookback 天销量的最小二乘线性斜率（单位：件/天）。 */
    private static double linearSlope(double[] series, int lookback) {
        int n = Math.min(lookback, series.length);
        if (n < 2) {
            return 0;
        }
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = series.length - n; i < series.length; i++) {
            double x = (double) i - (series.length - n);
            double y = series[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        return denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
    }

    private static Map<String, Map<LocalDate, Integer>> bucketDaily(List<Object[]> rows) {
        Map<String, Map<LocalDate, Integer>> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            String skuId = (String) row[0];
            LocalDate day = toLocalDate(row[1]);
            int qty = ((Number) row[2]).intValue();
            out.computeIfAbsent(skuId, k -> new HashMap<>())
                    .merge(day, qty, Integer::sum);
        }
        return out;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date utilDate) {
            return utilDate.toInstant().atZone(ZONE).toLocalDate();
        }
        if (value instanceof String text) {
            return LocalDate.parse(text);
        }
        throw new IllegalArgumentException("无法解析销量日期: " + value);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, Integer> toMap(List<Object[]> rows) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }
}
