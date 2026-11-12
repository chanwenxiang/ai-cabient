package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MarketingRoiRowDto;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        MarketingRoiService service = newRoiService();

        PromotionActivity activity = new PromotionActivity();
        activity.setActivityId(1L);
        activity.setActivityName("夏日冰饮满减周");
        activity.setActivityType("DISCOUNT");
        activity.setStatus("ACTIVE");
        activity.setBudgetCents(500000);
        activity.setUsedCents(0);
        when(activityRepository.findAllForRoi()).thenReturn(List.of(activity));

        CouponDefinition def = new CouponDefinition();
        def.setCouponDefId(2L);
        def.setCouponName("满 20 减 5 券");
        def.setActivityId(1L);
        when(couponDefinitionRepository.findByActivityIdIn(anyCollection()))
                .thenReturn(List.of(def));
        when(userCouponRepository.countGroupByCouponDefIds(anyCollection()))
                .thenReturn(List.of(couponAggRow(2L, 100L, 30L)));
        when(orderRepository.sumCouponOrdersByActivitySince(anyCollection(), any()))
                .thenReturn(List.of(orderAggRow(1L, 2L, 6000L, 700L)));

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

        // R20 P2-15：整条链路不得再回退成按活动/按券定义逐条查（三次 N+1）
        verify(activityRepository, never()).findAll();
        verify(couponDefinitionRepository, never()).findByActivityId(anyLong());
        verify(userCouponRepository, never()).countByCouponDefId(anyLong());
        verify(userCouponRepository, never()).countByCouponDefIdAndStatus(anyLong(), anyString());
        verify(userCouponRepository, never()).findByCouponDefId(anyLong());
        verify(orderRepository, never()).findByCouponIdInAndCreatedAtAfter(anyCollection(), any());
    }

    /**
     * 一个活动挂多个券定义时，领取/核销要**求和**（旧实现是逐券累加）；
     * 没被 group-by 结果覆盖到的券定义（一张都没发）按 0 记。
     */
    @Test
    void list_sumsCouponDefsOfSameActivity_andTreatsMissingAggregateAsZero() {
        MarketingRoiService service = newRoiService();

        PromotionActivity activity = new PromotionActivity();
        activity.setActivityId(9L);
        activity.setActivityName("双券活动");
        when(activityRepository.findAllForRoi()).thenReturn(List.of(activity));

        CouponDefinition a = new CouponDefinition();
        a.setCouponDefId(11L);
        a.setActivityId(9L);
        CouponDefinition b = new CouponDefinition();
        b.setCouponDefId(12L);
        b.setActivityId(9L);
        when(couponDefinitionRepository.findByActivityIdIn(anyCollection()))
                .thenReturn(List.of(a, b));
        // 只有 11 号券有发放记录，12 号券无聚合行 ⇒ 记 0
        when(userCouponRepository.countGroupByCouponDefIds(anyCollection()))
                .thenReturn(List.of(couponAggRow(11L, 8L, 4L)));
        when(orderRepository.sumCouponOrdersByActivitySince(anyCollection(), any()))
                .thenReturn(List.of());

        MarketingRoiRowDto row = service.list(30).get(0);

        assertEquals(8, row.claimedCount());
        assertEquals(4, row.usedCount());
        assertEquals(0.5, row.redeemRate(), 1e-9);
        assertEquals(0, row.orderCount());
        assertEquals(0, row.orderRevenueCents());
    }

    /** 无券活动也入表（OBS-006）：一行都不能少，发券/核销/营收全 0。 */
    @Test
    void list_keepsActivityWithoutCoupons() {
        MarketingRoiService service = newRoiService();

        PromotionActivity activity = new PromotionActivity();
        activity.setActivityId(5L);
        activity.setActivityName("纯折扣活动");
        when(activityRepository.findAllForRoi()).thenReturn(List.of(activity));
        when(couponDefinitionRepository.findByActivityIdIn(anyCollection())).thenReturn(List.of());
        when(orderRepository.sumCouponOrdersByActivitySince(anyCollection(), any())).thenReturn(List.of());

        List<MarketingRoiRowDto> rows = service.list(30);

        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).claimedCount());
        assertEquals(0.0, rows.get(0).redeemRate(), 1e-9);
    }

    private MarketingRoiService newRoiService() {
        return new MarketingRoiService(activityRepository, couponDefinitionRepository,
                userCouponRepository, orderRepository, playEventRepository, adCampaignRepository);
    }

    private static java.util.Map<String, Object> couponAggRow(Long defId, long claimed, long used) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("coupon_def_id", defId);
        row.put("claimed", claimed);
        row.put("used", used);
        return row;
    }

    private static java.util.Map<String, Object> orderAggRow(Long activityId, long count, long revenue, long discount) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("activity_id", activityId);
        row.put("order_count", count);
        row.put("revenue_cents", revenue);
        row.put("discount_cents", discount);
        return row;
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
