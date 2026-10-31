package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UserBehaviorRowDto;
import com.aicabinet.common.dto.UserBehaviorSummaryDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户行为轻量分析：活跃/新增/复购/沉睡/客单价，全部基于现有订单数据，无额外埋点。
 */
@Service
public class UserBehaviorAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /**
     * 沉睡名单返回上限。与 {@link UserRecallService#MAX_RECALL_USERS} 对齐：低于它导出的
     * 名单才完整（MA-002：原 20 条静默截断，KPI 全量而列表/导出/召回弹窗按截断值显示）。
     */
    private static final int MAX_DORMANT_RETURNED = 1000;

    private final CabinetOrderMapper orderRepository;
    private final UserInfoMapper userInfoRepository;

    public UserBehaviorAnalyticsService(CabinetOrderMapper orderRepository,
                                        UserInfoMapper userInfoRepository) {
        this.orderRepository = orderRepository;
        this.userInfoRepository = userInfoRepository;
    }

    /**
     * ⚠️ 口径（MA-001 修复后）：「活跃/新增/复购」是<b>固定窗口</b>指标——7d 恒为近 7 天、
     * 30d 恒为近 30 天（对齐友盟「过去 7 日/30 日活跃用户」的固定窗口语义），<b>不随</b>
     * {@code days} 参数漂移；{@code days} 只圈定「订单数/营收/客单价」这三个窗口指标。
     * 沉睡窗口恒为 30-90 天，也与 days 无关。
     */
    @Transactional(readOnly = true)
    public UserBehaviorSummaryDto summary(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant sinceWin = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        Instant since7 = LocalDate.now(ZONE).minusDays(6).atStartOfDay(ZONE).toInstant();
        Instant since30 = LocalDate.now(ZONE).minusDays(29).atStartOfDay(ZONE).toInstant();
        Instant dormantCutoff = LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toInstant();
        Instant dormantFloor = LocalDate.now(ZONE).minusDays(90).atStartOfDay(ZONE).toInstant();

        // 取三个窗口的并集起点（days>30 时 sinceWin 更早；days<30 时 since30 更早）
        Instant fetchSince = sinceWin.isBefore(since30) ? sinceWin : since30;
        List<CabinetOrder> windowOrders = paid(orderRepository.findByCreatedAtAfter(fetchSince));
        // S-P2-3：按用户 SQL 聚合，禁止 findAll 拉全表订单实体
        Map<Long, UserAgg> agg = loadUserAggFromDb();
        WindowMetrics windowMetrics = computeWindowMetrics(windowOrders, agg, sinceWin, since7, since30);
        List<UserBehaviorRowDto> dormant = findDormantUsers(agg, dormantCutoff, dormantFloor);
        int dormantTotal = dormant.size();
        dormant.sort(Comparator.comparing(UserBehaviorRowDto::lastOrderAt).reversed());
        if (dormant.size() > MAX_DORMANT_RETURNED) {
            dormant = dormant.subList(0, MAX_DORMANT_RETURNED);
        }

        List<UserBehaviorRowDto> topRepeat = agg.entrySet().stream()
                .filter(e -> e.getValue().totalOrders >= 2)
                .sorted((a, b) -> Long.compare(b.getValue().totalRevenue, a.getValue().totalRevenue))
                .limit(10)
                .map(e -> row(e.getKey(), e.getValue()))
                .toList();

        double avgOrder = windowMetrics.orders() > 0
                ? (double) windowMetrics.revenue() / windowMetrics.orders() : 0.0;
        double repeatRate7 = windowMetrics.active7() > 0
                ? (double) windowMetrics.repeat7() / windowMetrics.active7() : 0.0;

        return new UserBehaviorSummaryDto(
                windowMetrics.active7(),
                windowMetrics.active30(),
                windowMetrics.new7(),
                windowMetrics.new30(),
                windowMetrics.repeat7(),
                repeatRate7,
                dormantTotal,
                agg.size(),
                windowMetrics.orders(),
                windowMetrics.revenue(),
                avgOrder,
                topRepeat,
                dormant
        );
    }

    private Map<Long, UserAgg> loadUserAggFromDb() {
        Map<Long, UserAgg> agg = new HashMap<>();
        for (Object[] row : orderRepository.aggregatePaidOrdersByUser()) {
            Long userId = toLongObj(row[0]);
            if (userId == null) {
                continue;
            }
            UserAgg u = new UserAgg();
            u.totalOrders = toLong(row[1]);
            u.totalRevenue = toLong(row[2]);
            u.firstOrderAt = toInstant(row[3]);
            u.lastOrderAt = toInstant(row[4]);
            agg.put(userId, u);
        }
        return agg;
    }

    private static WindowMetrics computeWindowMetrics(List<CabinetOrder> orders, Map<Long, UserAgg> agg,
                                                      Instant sinceWin, Instant since7, Instant since30) {
        // 三个固定/窗口各自独立计数；orders 已是并集（fetchSince = min(sinceWin, since30)）
        Map<Long, Integer> count7 = new HashMap<>();
        Map<Long, Integer> count30 = new HashMap<>();
        long windowOrdersCount = 0;
        long windowRevenue = 0;
        for (CabinetOrder o : orders) {
            if (o.getUserId() == null) {
                continue;
            }
            Instant at = o.getCreatedAt();
            if (at != null && !at.isBefore(sinceWin)) {
                windowOrdersCount++;
                windowRevenue += o.getTotalAmountCents();
            }
            if (at != null && !at.isBefore(since7)) {
                count7.merge(o.getUserId(), 1, Integer::sum);
            }
            if (at != null && !at.isBefore(since30)) {
                count30.merge(o.getUserId(), 1, Integer::sum);
            }
        }
        int active7 = count7.size();
        int active30 = count30.size();
        int new7 = 0;
        int new30 = 0;
        int repeat7 = 0;
        for (Map.Entry<Long, Integer> e : count30.entrySet()) {
            UserAgg u = agg.get(e.getKey());
            Instant uFirst = u != null ? u.firstOrderAt : null;
            if (uFirst != null && uFirst.compareTo(since30) >= 0) {
                new30++;
            }
        }
        for (Map.Entry<Long, Integer> e : count7.entrySet()) {
            UserAgg u = agg.get(e.getKey());
            Instant uFirst = u != null ? u.firstOrderAt : null;
            if (uFirst != null && uFirst.compareTo(since7) >= 0) {
                new7++;
            }
            if (e.getValue() >= 2) {
                repeat7++;
            }
        }
        return new WindowMetrics(active7, active30, new7, new30, repeat7, windowOrdersCount, windowRevenue);
    }

    private List<UserBehaviorRowDto> findDormantUsers(Map<Long, UserAgg> agg, Instant dormantCutoff,
                                                      Instant dormantFloor) {
        List<UserBehaviorRowDto> dormant = new ArrayList<>();
        for (Map.Entry<Long, UserAgg> e : agg.entrySet()) {
            UserAgg u = e.getValue();
            if (u.lastOrderAt != null
                    && u.lastOrderAt.isBefore(dormantCutoff)
                    && u.lastOrderAt.isAfter(dormantFloor)) {
                dormant.add(row(e.getKey(), u));
            }
        }
        return dormant;
    }

    private record WindowMetrics(int active7, int active30, int new7, int new30, int repeat7,
                                 long orders, long revenue) {}

    private UserBehaviorRowDto row(Long userId, UserAgg u) {
        UserInfo info = userInfoRepository.findById(userId).orElse(null);
        String phone = info != null ? info.getPhoneNumber() : null;
        String name = info != null ? info.getName() : null;
        return new UserBehaviorRowDto(
                userId,
                phone,
                name,
                (int) u.totalOrders,
                BigDecimal.valueOf(u.totalRevenue).movePointLeft(2),
                u.totalRevenue,
                u.lastOrderAt
        );
    }

    private static List<CabinetOrder> paid(List<CabinetOrder> orders) {
        List<CabinetOrder> out = new ArrayList<>();
        for (CabinetOrder o : orders) {
            if (o.getUserId() != null && !"PENDING".equals(o.getStatus())) {
                out.add(o);
            }
        }
        return out;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Long toLongObj(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant();
        }
        return null;
    }

    private static final class UserAgg {
        long totalOrders;
        long totalRevenue;
        Instant firstOrderAt;
        Instant lastOrderAt;
    }
}
