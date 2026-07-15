package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final MerchantScopeService merchantScopeService;
    private final CabinetOrderLineMapper lineRepository;
    private final InventoryWriteOffMapper writeOffRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final SalesVelocityService salesVelocityService;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;

    public MerchantAnalyticsService(PermissionService permissionService,
                                    MerchantPortalGuard merchantPortalGuard,
                                    MerchantScopeService merchantScopeService,
                                    CabinetOrderLineMapper lineRepository,
                                    InventoryWriteOffMapper writeOffRepository,
                                    PullOffTaskMapper pullOffTaskRepository,
                                    SalesVelocityService salesVelocityService,
                                    SkuCatalogMapper skuCatalogRepository,
                                    DeviceSkuInventoryMapper inventoryRepository) {
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.merchantScopeService = merchantScopeService;
        this.lineRepository = lineRepository;
        this.writeOffRepository = writeOffRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.salesVelocityService = salesVelocityService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public MerchantAnalyticsOverviewDto overview(Long userId, int days) {
        requireAnalytics(userId);
        int window = clampDays(days);
        Set<String> deviceIds = requireScopedDevices(userId);
        if (deviceIds.isEmpty()) {
            return emptyOverview(window);
        }
        Instant since = windowStart(window);
        long revenue = lineRepository.sumRevenueByDeviceIdsSince(deviceIds, since);
        long cogs = lineRepository.sumCogsByDeviceIdsSince(deviceIds, since);
        long writeOff = writeOffRepository.sumCostCentsByDeviceIdsSince(deviceIds, since);
        List<MerchantSkuSalesDto> topSkus = mapSkuSales(
                lineRepository.skuBreakdownByDevicesSince(deviceIds, since), 20);
        return new MerchantAnalyticsOverviewDto(window, revenue, cogs, revenue - cogs, writeOff, topSkus);
    }

    @Transactional(readOnly = true)
    public List<MerchantSkuSalesDto> skuSales(Long userId, int days, String deviceId) {
        requireAnalytics(userId);
        int window = clampDays(days);
        Set<String> deviceIds = resolveDeviceFilter(userId, deviceId);
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return mapSkuSales(lineRepository.skuBreakdownByDevicesSince(deviceIds, windowStart(window)), 50);
    }

    @Transactional(readOnly = true)
    public List<MerchantSkuVelocityDto> velocity(Long userId, String deviceId) {
        requireAnalytics(userId);
        merchantScopeService.requireDeviceAccess(userId, deviceId);
        Map<String, SalesVelocityService.SkuVelocity> velocities = salesVelocityService.velocityBySku(deviceId);
        if (velocities.isEmpty()) {
            return List.of();
        }
        Map<String, String> skuNames = skuCatalogRepository.findAllById(velocities.keySet()).stream()
                .collect(HashMap::new, (m, s) -> m.put(s.getSkuId(), s.getSkuName()), HashMap::putAll);
        return velocities.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().soldQty7d(), a.getValue().soldQty7d()))
                .map(e -> {
                    SalesVelocityService.SkuVelocity v = e.getValue();
                    return new MerchantSkuVelocityDto(
                            e.getKey(),
                            skuNames.getOrDefault(e.getKey(), e.getKey()),
                            v.soldQty7d(),
                            v.soldQty14d(),
                            v.avgDailySales(),
                            v.ropPoint());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public MerchantExpirySummaryDto expirySummary(Long userId) {
        requireAnalytics(userId);
        Set<String> deviceIds = requireScopedDevices(userId);
        if (deviceIds.isEmpty()) {
            return new MerchantExpirySummaryDto(0, 0, 0);
        }
        Instant since30 = LocalDate.now(ZONE).minusDays(29).atStartOfDay(ZONE).toInstant();
        return new MerchantExpirySummaryDto(
                pullOffTaskRepository.countByStatusAndDeviceIdIn("OPEN", deviceIds),
                writeOffRepository.sumQuantityByDeviceIdsSince(deviceIds, since30),
                writeOffRepository.sumCostCentsByDeviceIdsSince(deviceIds, since30));
    }

    @Transactional(readOnly = true)
    public List<MerchantSkuPerformanceDto> skuPerformance(Long userId, int days) {
        requireAnalytics(userId);
        int window = clampDays(days);
        Set<String> deviceIds = requireScopedDevices(userId);
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> sales = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : lineRepository.skuBreakdownByDevicesSince(deviceIds, windowStart(window))) {
            String skuId = String.valueOf(row[0]);
            names.put(skuId, row[1] != null ? String.valueOf(row[1]) : skuId);
            sales.put(skuId, new long[]{toLong(row[2]), toLong(row[3]), toLong(row[4])});
        }
        Map<String, Long> stock = inventoryRepository.findByIdDeviceIdIn(deviceIds).stream()
                .collect(Collectors.groupingBy(i -> i.getId().getSkuId(),
                        Collectors.summingLong(i -> i.getQuantity())));
        Set<String> skuIds = new HashSet<>(stock.keySet());
        skuIds.addAll(sales.keySet());
        skuCatalogRepository.findAllById(skuIds).forEach(s -> names.put(s.getSkuId(), s.getSkuName()));

        List<Long> positiveSales = sales.values().stream().map(v -> v[0]).filter(v -> v > 0).sorted().toList();
        long median = positiveSales.isEmpty() ? 0 : positiveSales.get(positiveSales.size() / 2);
        return skuIds.stream().map(skuId -> {
                    long[] value = sales.getOrDefault(skuId, new long[3]);
                    long qty = value[0];
                    long revenue = value[1];
                    long margin = revenue - value[2];
                    long currentStock = stock.getOrDefault(skuId, 0L);
                    double daily = (double) qty / window;
                    String level = performanceLevel(qty, median);
                    Double cover = daily > 0 ? currentStock / daily : null;
                    return new MerchantSkuPerformanceDto(
                            skuId, names.getOrDefault(skuId, skuId), qty, revenue, margin,
                            revenue > 0 ? (double) margin / revenue : 0.0,
                            currentStock, daily, cover, level, recommendation(level, cover));
                })
                .sorted(Comparator.comparingLong(MerchantSkuPerformanceDto::qtySold).reversed())
                .toList();
    }

    private void requireAnalytics(Long userId) {
        permissionService.requirePermission(userId, "merchant:analytics:view");
        merchantPortalGuard.requireAccess(userId);
    }

    private Set<String> requireScopedDevices(Long userId) {
        Set<String> allowed = merchantScopeService.allowedDeviceIds(userId);
        if (allowed == null) {
            return merchantScopeService.allowedDevices(userId).stream()
                    .map(d -> d.getDeviceId())
                    .collect(Collectors.toSet());
        }
        return allowed;
    }

    private Set<String> resolveDeviceFilter(Long userId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            String dev = deviceId.trim();
            merchantScopeService.requireDeviceAccess(userId, dev);
            return Set.of(dev);
        }
        return requireScopedDevices(userId);
    }

    private static int clampDays(int days) {
        return Math.min(Math.max(days, 1), 90);
    }

    private static Instant windowStart(int days) {
        return LocalDate.now(ZONE).minusDays(days - 1L).atStartOfDay(ZONE).toInstant();
    }

    private static MerchantAnalyticsOverviewDto emptyOverview(int window) {
        return new MerchantAnalyticsOverviewDto(window, 0, 0, 0, 0, List.of());
    }

    private static List<MerchantSkuSalesDto> mapSkuSales(List<Object[]> rows, int limit) {
        return rows.stream()
                .limit(limit)
                .map(row -> {
                    long qty = ((Number) row[2]).longValue();
                    long revenue = ((Number) row[3]).longValue();
                    long cogs = ((Number) row[4]).longValue();
                    return new MerchantSkuSalesDto(
                            (String) row[0],
                            row[1] != null ? (String) row[1] : (String) row[0],
                            qty,
                            revenue,
                            cogs,
                            revenue - cogs);
                })
                .toList();
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String performanceLevel(long qty, long median) {
        if (qty == 0) return "NO_SALES";
        if (median > 0 && qty >= median * 2) return "BEST_SELLER";
        if (median > 0 && qty * 2 < median) return "SLOW_MOVER";
        return "NORMAL";
    }

    private static String recommendation(String level, Double daysOfCover) {
        if ("NO_SALES".equals(level)) return "检查陈列与定价，连续无销量可考虑下架";
        if ("SLOW_MOVER".equals(level)) return "减少补货，尝试促销或替换商品";
        if ("BEST_SELLER".equals(level) && daysOfCover != null && daysOfCover < 3) return "畅销且库存不足，建议立即补货";
        if ("BEST_SELLER".equals(level)) return "保持陈列，避免缺货并测试关联销售";
        if (daysOfCover != null && daysOfCover > 30) return "库存覆盖过高，建议降低补货量";
        return "保持当前策略并持续观察";
    }
}
