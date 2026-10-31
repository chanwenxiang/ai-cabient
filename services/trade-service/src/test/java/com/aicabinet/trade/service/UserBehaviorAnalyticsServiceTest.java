package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UserBehaviorSummaryDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserBehaviorAnalyticsServiceTest {

    @Mock private CabinetOrderMapper orderRepository;
    @Mock private UserInfoMapper userInfoRepository;

    private static CabinetOrder order(long userId, int amountCents, Instant at, String status) {
        CabinetOrder o = new CabinetOrder();
        o.setOrderId("O" + userId + "-" + at.toEpochMilli());
        o.setUserId(userId);
        o.setTotalAmountCents(amountCents);
        o.setStatus(status);
        o.setCreatedAt(at);
        return o;
    }

    @Test
    void summary_shouldComputeActiveNewRepeatDormantAndAov() {
        UserBehaviorAnalyticsService service = new UserBehaviorAnalyticsService(orderRepository, userInfoRepository);
        Instant now = Instant.now();
        Instant d60 = now.minus(60, ChronoUnit.DAYS);
        Instant d45 = now.minus(45, ChronoUnit.DAYS);
        Instant d100 = now.minus(100, ChronoUnit.DAYS);

        // 窗口内订单
        List<CabinetOrder> window = List.of(
                order(1L, 1000, now, "PAID"),
                order(1L, 2000, now.minus(1, ChronoUnit.DAYS), "PAID"),
                order(1L, 3000, now.minus(2, ChronoUnit.DAYS), "PAID"),
                order(2L, 1500, now, "PAID"),
                order(2L, 500, now, "PENDING") // 待支付不计入
        );

        // 按用户聚合（替代 findAll）：userId, orderCount, revenue, firstAt, lastAt
        List<Object[]> userAgg = List.of(
                new Object[]{1L, 3L, 6000L, now.minus(2, ChronoUnit.DAYS), now},
                new Object[]{2L, 3L, 3000L, d60.minus(1, ChronoUnit.DAYS), now},
                new Object[]{3L, 1L, 600L, d45, d45},
                new Object[]{4L, 1L, 400L, d100, d100}
        );

        when(orderRepository.findByCreatedAtAfter(any())).thenReturn(window);
        when(orderRepository.aggregatePaidOrdersByUser()).thenReturn(userAgg);

        UserBehaviorSummaryDto s = service.summary(30);

        assertEquals(2, s.activeUsers7d());
        assertEquals(2, s.activeUsers30d());
        assertEquals(1, s.newUsers7d());   // 仅 user1 首单在窗口
        assertEquals(1, s.newUsers30d());
        assertEquals(1, s.repeatBuyer7d()); // user1 窗口 ≥2 单
        assertEquals(0.5, s.repeatPurchaseRate7d(), 0.001);
        assertEquals(1, s.dormantUsers30d()); // user3（45 天）
        assertEquals(4, s.totalUsers());      // user1/2/3/4 均有有效订单
        assertEquals(4, s.totalOrders());     // 窗口有效订单 3 + 1
        assertEquals(7500, s.totalRevenueCents());
        assertEquals(1875.0, s.avgOrderValueCents(), 0.001);
        verify(orderRepository, never()).findAll();
    }

    /** MA-001：「活跃/新增 30 天」是固定窗口，不随 days 收窄——days=7 时 30 天口径仍须数到 7 天外的用户。 */
    @Test
    void summary_days7_active30AndNew30_stayFixed30dWindow() {
        UserBehaviorAnalyticsService service = new UserBehaviorAnalyticsService(orderRepository, userInfoRepository);
        Instant now = Instant.now();
        Instant d15 = now.minus(15, ChronoUnit.DAYS); // 7 天外、30 天内

        List<CabinetOrder> orders = List.of(
                order(1L, 1000, now, "PAID"),
                order(2L, 500, d15, "PAID"));
        List<Object[]> userAgg = List.of(
                new Object[]{1L, 1L, 1000L, now, now},
                new Object[]{2L, 1L, 500L, d15, d15});

        when(orderRepository.findByCreatedAtAfter(any())).thenReturn(orders);
        when(orderRepository.aggregatePaidOrdersByUser()).thenReturn(userAgg);

        UserBehaviorSummaryDto s = service.summary(7);

        assertEquals(1, s.activeUsers7d());
        assertEquals(2, s.activeUsers30d()); // 修复前：0（30 天口径被 7 天窗口吞掉）
        assertEquals(1, s.newUsers7d());
        assertEquals(2, s.newUsers30d());    // 修复前：0
        assertEquals(1, s.totalOrders());    // 订单/营收仍是 days 窗口指标
    }

    /** MA-001：days=90 时「30 天」口径不得被 90 天窗口虚增；沉睡名单 20 条不再静默截断（MA-002）。 */
    @Test
    void summary_days90_active30_notInflated_andDormantList_notTruncatedAt20() {
        UserBehaviorAnalyticsService service = new UserBehaviorAnalyticsService(orderRepository, userInfoRepository);
        Instant now = Instant.now();
        Instant d45 = now.minus(45, ChronoUnit.DAYS);

        List<Object[]> userAgg = new java.util.ArrayList<>();
        userAgg.add(new Object[]{1L, 1L, 100L, now, now});
        List<CabinetOrder> orders = new java.util.ArrayList<>(List.of(order(1L, 100, now, "PAID")));
        for (long uid = 100; uid < 130; uid++) { // 30 个沉睡用户（45 天前最后一单）
            userAgg.add(new Object[]{uid, 1L, 100L, d45, d45});
            orders.add(order(uid, 100, d45, "PAID"));
        }

        when(orderRepository.findByCreatedAtAfter(any())).thenReturn(orders);
        when(orderRepository.aggregatePaidOrdersByUser()).thenReturn(userAgg);
        when(userInfoRepository.findById(any(Long.class)))
                .thenAnswer(inv -> java.util.Optional.empty());

        UserBehaviorSummaryDto s = service.summary(90);

        assertEquals(1, s.activeUsers7d());
        assertEquals(1, s.activeUsers30d()); // 修复前：31（90 天窗口活跃全部计入「30 天」）
        assertEquals(30, s.dormantUsers30d());
        assertEquals(30, s.dormantUsers().size()); // 修复前：截 20
    }
}
