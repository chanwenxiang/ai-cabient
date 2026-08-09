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
        // 全量订单：含老用户与沉睡/长期未动
        List<CabinetOrder> all = new java.util.ArrayList<>(window);
        all.add(order(2L, 800, d60, "PAID"));
        all.add(order(2L, 700, d60.minus(1, ChronoUnit.DAYS), "PAID"));
        all.add(order(3L, 600, d45, "PAID"));
        all.add(order(4L, 400, d100, "PAID"));

        when(orderRepository.findByCreatedAtAfter(any())).thenReturn(window);
        when(orderRepository.findAll()).thenReturn(all);

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
    }
}
