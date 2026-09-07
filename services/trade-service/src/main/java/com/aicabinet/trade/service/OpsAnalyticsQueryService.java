package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminChannelBreakdownDto;
import com.aicabinet.common.dto.AdminChannelStatDto;
import com.aicabinet.common.dto.AdminDailyStatDto;
import com.aicabinet.common.dto.AdminOpsDailyDto;
import com.aicabinet.common.dto.AdminOpsTrendDto;
import com.aicabinet.common.dto.AdminTrendDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营分析只读查询（原 AdminDashboardService 分析簇）。
 * Pass 3F PR-A：从神类拆出，门面仍可委托本类。
 */
@Service
public class OpsAnalyticsQueryService {

    private static final String PERM_OPS_DASHBOARD_VIEW = "ops:dashboard:view";
    private static final String PERM_OPS_ANALYTICS_VIEW = "ops:analytics:view";
    private static final List<SessionState> CLOSED_STATES = List.of(
            SessionState.COMPLETED, SessionState.DISPUTED);

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final CabinetOrderMapper orderRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final RechargeOrderMapper rechargeOrderRepository;

    public OpsAnalyticsQueryService(PermissionService permissionService,
                                    MerchantScopeService merchantScopeService,
                                    CabinetOrderMapper orderRepository,
                                    ShoppingSessionMapper sessionRepository,
                                    RechargeOrderMapper rechargeOrderRepository) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.orderRepository = orderRepository;
        this.sessionRepository = sessionRepository;
        this.rechargeOrderRepository = rechargeOrderRepository;
    }

    public AdminTrendDto orderTrend(Long operatorId) {
        return orderTrend(operatorId, 7);
    }

    public AdminTrendDto orderTrend(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            buckets.put(start.plusDays(i), new long[]{0, 0});
        }
        for (CabinetOrder order : queryTrendOrders(operatorId, since)) {
            LocalDate day = order.getCreatedAt().atZone(zone).toLocalDate();
            long[] bucket = buckets.get(day);
            if (bucket != null) {
                bucket[0]++;
                bucket[1] += order.getTotalAmountCents();
            }
        }
        List<AdminDailyStatDto> points = buckets.entrySet().stream()
                .map(e -> new AdminDailyStatDto(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1]))
                .toList();
        return new AdminTrendDto(points);
    }

    public AdminChannelBreakdownDto channelBreakdown(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant since = today.minusDays(window - 1L).atStartOfDay(zone).toInstant();

        Map<String, long[]> orderBuckets = new java.util.LinkedHashMap<>();
        for (CabinetOrder order : queryTrendOrders(operatorId, since)) {
            String channel = normalizePayChannel(order.getPayChannel());
            long[] bucket = orderBuckets.computeIfAbsent(channel, k -> new long[]{0, 0});
            bucket[0]++;
            bucket[1] += Math.max(order.getTotalAmountCents(), 0);
        }

        Map<String, long[]> rechargeBuckets = new java.util.LinkedHashMap<>();
        for (RechargeOrder recharge : rechargeOrderRepository.findByCreatedAtAfter(since)) {
            if (!"PAID".equalsIgnoreCase(recharge.getStatus())) {
                continue;
            }
            String channel = normalizePayChannel(recharge.getChannel());
            long[] bucket = rechargeBuckets.computeIfAbsent(channel, k -> new long[]{0, 0});
            bucket[0]++;
            bucket[1] += Math.max(recharge.getAmountCents(), 0);
        }

        return new AdminChannelBreakdownDto(
                toChannelStats(orderBuckets),
                toChannelStats(rechargeBuckets)
        );
    }

    public AdminOpsTrendDto opsTrend(Long operatorId) {
        return opsTrend(operatorId, 7);
    }

    public AdminOpsTrendDto opsTrend(Long operatorId, int days) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DASHBOARD_VIEW, PERM_OPS_ANALYTICS_VIEW);
        int window = normalizeTrendDays(days);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = initDailyCountBuckets(start, window);
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        accumulateOpsTrendSessions(buckets,
                sessionRepository.findByStateInAndUpdatedAtAfter(CLOSED_STATES, since),
                scopedDevices, zone);
        return new AdminOpsTrendDto(toOpsDailyPoints(buckets));
    }

    static int normalizeTrendDays(int days) {
        if (days >= 90) {
            return 90;
        }
        if (days >= 30) {
            return 30;
        }
        return 7;
    }

    static List<AdminOpsDailyDto> toOpsDailyPoints(Map<LocalDate, long[]> buckets) {
        return buckets.entrySet().stream()
                .map(e -> {
                    long completed = e.getValue()[0];
                    long disputed = e.getValue()[1];
                    long total = completed + disputed;
                    double recognitionRate = total > 0 ? (double) completed / total : 1.0;
                    double disputeRate = total > 0 ? (double) disputed / total : 0.0;
                    return new AdminOpsDailyDto(
                            e.getKey().toString(), completed, disputed, recognitionRate, disputeRate);
                })
                .toList();
    }

    private static Map<LocalDate, long[]> initDailyCountBuckets(LocalDate start, int window) {
        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            buckets.put(start.plusDays(i), new long[]{0, 0});
        }
        return buckets;
    }

    private static void accumulateOpsTrendSessions(Map<LocalDate, long[]> buckets,
                                                   List<ShoppingSession> sessions,
                                                   Set<String> scopedDevices, ZoneId zone) {
        for (ShoppingSession session : sessions) {
            if (scopedDevices != null && !scopedDevices.contains(session.getDeviceId())) {
                continue;
            }
            LocalDate day = session.getUpdatedAt().atZone(zone).toLocalDate();
            long[] bucket = buckets.get(day);
            if (bucket != null) {
                if (session.getState() == SessionState.COMPLETED) {
                    bucket[0]++;
                } else if (session.getState() == SessionState.DISPUTED) {
                    bucket[1]++;
                }
            }
        }
    }

    private static List<AdminChannelStatDto> toChannelStats(Map<String, long[]> buckets) {
        return buckets.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .map(e -> new AdminChannelStatDto(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    private static String normalizePayChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "UNKNOWN";
        }
        return channel.trim().toUpperCase();
    }

    private List<CabinetOrder> queryTrendOrders(Long operatorId, Instant since) {
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return List.of();
        }
        if (scopedDevices == null) {
            return orderRepository.findByCreatedAtAfter(since);
        }
        return orderRepository.findByDeviceIdInAndCreatedAtAfter(scopedDevices, since);
    }
}
