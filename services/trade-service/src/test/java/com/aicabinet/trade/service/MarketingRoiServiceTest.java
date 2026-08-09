package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MarketingRoiRowDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.UserCoupon;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingRoiServiceTest {

    @Mock private PromotionActivityMapper activityRepository;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private CabinetOrderMapper orderRepository;

    @Test
    void list_shouldAggregateClaimRedeemAndRevenuePerActivity() {
        MarketingRoiService service = new MarketingRoiService(
                activityRepository, couponDefinitionRepository, userCouponRepository, orderRepository);

        PromotionActivity activity = new PromotionActivity();
        activity.setActivityId(1L);
        activity.setActivityName("夏日冰饮满减周");
        activity.setActivityType("DISCOUNT");
        activity.setStatus("ACTIVE");
        activity.setBudgetCents(500000);
        activity.setUsedCents(0);
        when(activityRepository.findAll()).thenReturn(List.of(activity));

        CouponDefinition def = new CouponDefinition();
        def.setCouponDefId(2L);
        def.setCouponName("满 20 减 5 券");
        def.setActivityId(1L);
        when(couponDefinitionRepository.findByActivityId(1L)).thenReturn(List.of(def));
        when(userCouponRepository.countByCouponDefId(2L)).thenReturn(100L);
        when(userCouponRepository.countByCouponDefIdAndStatus(2L, "USED")).thenReturn(30L);

        UserCoupon c1 = new UserCoupon();
        c1.setCouponId(101L);
        c1.setCouponDefId(2L);
        UserCoupon c2 = new UserCoupon();
        c2.setCouponId(102L);
        c2.setCouponDefId(2L);
        when(userCouponRepository.findByCouponDefId(2L)).thenReturn(List.of(c1, c2));

        CabinetOrder o1 = new CabinetOrder();
        o1.setTotalAmountCents(5000);
        o1.setCouponDiscountCents(500);
        CabinetOrder o2 = new CabinetOrder();
        o2.setTotalAmountCents(1000);
        o2.setCouponDiscountCents(200);
        when(orderRepository.findByCouponIdInAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(o1, o2));

        List<MarketingRoiRowDto> rows = service.list(30);

        assertEquals(1, rows.size());
        MarketingRoiRowDto row = rows.get(0);
        assertEquals("夏日冰饮满减周", row.activityName());
        assertEquals(100, row.claimedCount());
        assertEquals(30, row.usedCount());
        assertEquals(0.3, row.redeemRate(), 0.001);
        assertEquals(2, row.orderCount());
        assertEquals(6000, row.orderRevenueCents());
        assertEquals(700, row.discountCents());
    }
}
