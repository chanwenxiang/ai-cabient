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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingRoiServiceTest {

    @Mock private PromotionActivityMapper activityRepository;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private com.aicabinet.trade.mapper.AdPlayEventMapper playEventRepository;
    @Mock private com.aicabinet.trade.mapper.AdCampaignMapper adCampaignRepository;

    @Test
    void list_shouldAggregateClaimRedeemAndRevenuePerActivity() {
        MarketingRoiService service = new MarketingRoiService(
                activityRepository, couponDefinitionRepository, userCouponRepository, orderRepository,
                playEventRepository, adCampaignRepository);

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

    @Test
    void placements_shouldDivideByAttributedUsersOnly_andReportPeakAndUnattributed() {
        MarketingRoiService service = new MarketingRoiService(
                activityRepository, couponDefinitionRepository, userCouponRepository, orderRepository,
                playEventRepository, adCampaignRepository);

        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("position", "POPUP_BEFORE_OPEN");
        row.put("subject_kind", "ACTIVITY");
        row.put("subject_id", 1L);
        row.put("exposure_count", 10L);
        row.put("exposure_users", 4L);
        row.put("click_count", 2L);
        row.put("unattributed_count", 6L);
        when(playEventRepository.aggregatePlacementsSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(row));

        java.util.Map<String, Object> peak = new java.util.LinkedHashMap<>();
        peak.put("position", "POPUP_BEFORE_OPEN");
        peak.put("subject_kind", "ACTIVITY");
        peak.put("subject_id", 1L);
        peak.put("max_exposure_per_user", 5L);
        when(playEventRepository.peakExposurePerUserSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(peak));

        PromotionActivity activity = new PromotionActivity();
        activity.setActivityId(1L);
        activity.setActivityName("夏日冰饮满减周");
        when(activityRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(activity));

        var out = service.placements(30);

        assertEquals(1, out.size());
        var r = out.get(0);
        // 人均 = 曝光 / 已归因人数（10/4），未归因那 6 行不进分母
        assertEquals(2.5, r.exposurePerUser(), 1e-9);
        assertEquals(5L, r.maxExposurePerUser());
        assertEquals(6L, r.unattributedCount());
        assertEquals("POPUP_BEFORE_OPEN", r.position());
        assertEquals("夏日冰饮满减周", r.subjectName());
    }

    @Test
    void placements_withoutAttributedRows_shouldNotInventAnAverage() {
        MarketingRoiService service = new MarketingRoiService(
                activityRepository, couponDefinitionRepository, userCouponRepository, orderRepository,
                playEventRepository, adCampaignRepository);

        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("position", "BANNER");
        row.put("subject_kind", "AD_CAMPAIGN");
        row.put("subject_id", 7L);
        row.put("exposure_count", 9L);
        row.put("exposure_users", 0L);
        row.put("click_count", 0L);
        row.put("unattributed_count", 9L);
        when(playEventRepository.aggregatePlacementsSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(row));
        when(playEventRepository.peakExposurePerUserSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var r = service.placements(30).get(0);
        // 全部未归因 ⇒ 人均必须记 0 而不是 9/0 的 NaN/Infinity，否则前端列会炸
        assertEquals(0.0, r.exposurePerUser(), 1e-9);
        assertEquals(0L, r.maxExposurePerUser());
    }
}
