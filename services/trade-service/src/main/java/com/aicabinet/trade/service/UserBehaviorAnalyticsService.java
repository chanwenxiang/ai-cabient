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
import java.time.Instant;
import java.time.LocalDate;
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

    private final CabinetOrderMapper orderRepository;
    private final UserInfoMapper userInfoRepository;

    public UserBehaviorAnalyticsService(CabinetOrderMapper orderRepository,
                                        UserInfoMapper userInfoRepository) {
        this.orderRepository = orderRepository;
        this.userInfoRepository = userInfoRepository;
    }

    @Transactional(readOnly = true)
    public UserBehaviorSummaryDto summary(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        Instant since30 = LocalDate.now(ZONE).minusDays(29).atStartOfDay(ZONE).toInstant();
        Instant dormantCutoff = LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toInstant();
        Instant dormantFloor = LocalDate.now(ZONE).minusDays(90).atStartOfDay(ZONE).toInstant();

        List<CabinetOrder> windowOrders = paid(orderRepository.findByCreatedAtAfter(since));
        List<CabinetOrder> allOrders = paid(orderRepository.findAll());

        // 用户聚合
        Map<Long, UserAgg> agg = new HashMap<>();
        for (CabinetOrder o : allOrders) {
            if (o.getUserId() == null) {
                continue;
            }
            UserAgg u = agg.computeIfAbsent(o.getUserId(), k -> new UserAgg());
            u.totalOrders++;
            u.totalRevenue += o.getTotalAmountCents();
            if (o.getCreatedAt() != null) {
                if (u.firstOrderAt == null || o.getCreatedAt().isBefore(u.firstOrderAt)) {
                    u.firstOrderAt = o.getCreatedAt();
                }
                if (u.lastOrderAt == null || o.getCreatedAt().isAfter(u.lastOrderAt)) {
                    u.lastOrderAt = o.getCreatedAt();
                }
            }
        }

        // 窗口期指标
        Map<Long, Integer> windowCount = new HashMap<>();
        long windowOrdersCount = 0;
        long windowRevenue = 0;
        for (CabinetOrder o : windowOrders) {
            if (o.getUserId() == null) {
                continue;
            }
            windowCount.merge(o.getUserId(), 1, Integer::sum);
            windowOrdersCount++;
            windowRevenue += o.getTotalAmountCents();
        }
        int active7 = 0;
        int active30 = 0;
        int new7 = 0;
        int new30 = 0;
        int repeat7 = 0;
        for (Map.Entry<Long, Integer> e : windowCount.entrySet()) {
            Long uid = e.getKey();
            UserAgg u = agg.get(uid);
            Instant uFirst = u != null ? u.firstOrderAt : null;
            int cnt = e.getValue();
            if (cnt >= 2) {
                repeat7++;
            }
            if (uFirst != null && uFirst.compareTo(since) >= 0) {
                new7++;
            }
            if (uFirst != null && uFirst.compareTo(since30) >= 0) {
                new30++;
            }
            active7++;
            active30++;
        }

        // 沉睡用户：30~90 天前有单、近 30 天无单
        List<UserBehaviorRowDto> dormant = new ArrayList<>();
        for (Map.Entry<Long, UserAgg> e : agg.entrySet()) {
            UserAgg u = e.getValue();
            if (u.lastOrderAt != null
                    && u.lastOrderAt.isBefore(dormantCutoff)
                    && u.lastOrderAt.isAfter(dormantFloor)) {
                dormant.add(row(e.getKey(), u));
            }
        }
        int dormantTotal = dormant.size();
        dormant.sort(Comparator.comparing(UserBehaviorRowDto::lastOrderAt).reversed());
        if (dormant.size() > 20) {
            dormant = dormant.subList(0, 20);
        }

        List<UserBehaviorRowDto> topRepeat = agg.entrySet().stream()
                .filter(e -> e.getValue().totalOrders >= 2)
                .sorted((a, b) -> Long.compare(b.getValue().totalRevenue, a.getValue().totalRevenue))
                .limit(10)
                .map(e -> row(e.getKey(), e.getValue()))
                .toList();

        long totalOrders = windowOrdersCount;
        long totalRevenue = windowRevenue;
        double avgOrder = totalOrders > 0 ? (double) totalRevenue / totalOrders : 0.0;
        double repeatRate7 = active7 > 0 ? (double) repeat7 / active7 : 0.0;

        return new UserBehaviorSummaryDto(
                active7,
                active30,
                new7,
                new30,
                repeat7,
                repeatRate7,
                dormantTotal,
                agg.size(),
                totalOrders,
                totalRevenue,
                avgOrder,
                topRepeat,
                dormant
        );
    }

    private UserBehaviorRowDto row(Long userId, UserAgg u) {
        UserInfo info = userInfoRepository.findById(userId).orElse(null);
        String phone = info != null ? info.getPhoneNumber() : null;
        String name = info != null ? info.getName() : null;
        BigDecimal last = u.lastOrderAt == null ? null
                : BigDecimal.valueOf(u.lastOrderAt.toEpochMilli());
        return new UserBehaviorRowDto(
                userId,
                phone,
                name,
                (int) u.totalOrders,
                BigDecimal.valueOf(u.totalRevenue, 2),
                last
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

    private static final class UserAgg {
        long totalOrders;
        long totalRevenue;
        Instant firstOrderAt;
        Instant lastOrderAt;
    }
}
