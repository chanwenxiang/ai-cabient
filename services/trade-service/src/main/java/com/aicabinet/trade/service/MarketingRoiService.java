package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MarketingRoiRowDto;
import com.aicabinet.common.dto.PlacementEffectRowDto;
import com.aicabinet.trade.domain.AdCampaign;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.UserCoupon;
import com.aicabinet.trade.mapper.AdCampaignMapper;
import com.aicabinet.trade.mapper.AdPlayEventMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 营销活动效果分析：发券 → 核销 → 带动营收 / 核销面额，评估活动 ROI。
 */
@Service
public class MarketingRoiService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PromotionActivityMapper activityRepository;
    private final CouponDefinitionMapper couponDefinitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final CabinetOrderMapper orderRepository;
    private final AdPlayEventMapper playEventRepository;
    private final AdCampaignMapper adCampaignRepository;

    public MarketingRoiService(PromotionActivityMapper activityRepository,
                               CouponDefinitionMapper couponDefinitionRepository,
                               UserCouponMapper userCouponRepository,
                               CabinetOrderMapper orderRepository,
                               AdPlayEventMapper playEventRepository,
                               AdCampaignMapper adCampaignRepository) {
        this.activityRepository = activityRepository;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.orderRepository = orderRepository;
        this.playEventRepository = playEventRepository;
        this.adCampaignRepository = adCampaignRepository;
    }

    /**
     * 活动位效果（按展示位 × 主体分桶，批次 T）。
     *
     * <p>人均与峰值都只按**已归因**行算，未归因行单列出来（{@code unattributedCount}）——
     * 把它塞进分母会把人均压成假低值，那样"有没有被一个人刷高"就再也看不出来了。
     */
    @Transactional(readOnly = true)
    public List<PlacementEffectRowDto> placements(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        List<Map<String, Object>> rows = playEventRepository.aggregatePlacementsSince(since);
        Map<String, Long> peaks = new HashMap<>();
        for (Map<String, Object> p : playEventRepository.peakExposurePerUserSince(since)) {
            peaks.put(peakKey(p), asLong(p.get("max_exposure_per_user")));
        }
        Map<String, String> names = subjectNames(rows);
        List<PlacementEffectRowDto> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long exposure = asLong(r.get("exposure_count"));
            long users = asLong(r.get("exposure_users"));
            out.add(new PlacementEffectRowDto(
                    String.valueOf(r.get("position")),
                    String.valueOf(r.get("subject_kind")),
                    r.get("subject_id") == null ? null : asLong(r.get("subject_id")),
                    names.getOrDefault(subjectKey(r), null),
                    exposure,
                    users,
                    asLong(r.get("click_count")),
                    users > 0 ? (double) exposure / users : 0.0,
                    peaks.getOrDefault(peakKey(r), 0L),
                    asLong(r.get("unattributed_count"))));
        }
        out.sort((a, b) -> Long.compare(b.exposureCount(), a.exposureCount()));
        return out;
    }

    /** 主体名一次批量取回：活动走 PromotionActivity，广告计划走 AdCampaign。 */
    private Map<String, String> subjectNames(List<Map<String, Object>> rows) {
        Set<Long> activityIds = new LinkedHashSet<>();
        Set<Long> campaignIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Long id = r.get("subject_id") == null ? null : asLong(r.get("subject_id"));
            if (id == null) {
                continue;
            }
            if ("ACTIVITY".equals(String.valueOf(r.get("subject_kind")))) {
                activityIds.add(id);
            } else {
                campaignIds.add(id);
            }
        }
        Map<String, String> names = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (PromotionActivity a : activityRepository.findAllById(activityIds)) {
                names.put("ACT|" + a.getActivityId(), a.getActivityName());
            }
        }
        if (!campaignIds.isEmpty()) {
            for (AdCampaign c : adCampaignRepository.selectBatchIds(campaignIds)) {
                names.put("CAM|" + c.getCampaignId(), c.getName());
            }
        }
        return names;
    }

    private static String subjectKey(Map<String, Object> row) {
        return ("ACTIVITY".equals(String.valueOf(row.get("subject_kind"))) ? "ACT|" : "CAM|")
                + row.get("subject_id");
    }

    private static String peakKey(Map<String, Object> row) {
        return String.valueOf(row.get("position")) + '|' + row.get("subject_kind") + '|' + row.get("subject_id");
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @Transactional(readOnly = true)
    public List<MarketingRoiRowDto> list(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        List<MarketingRoiRowDto> out = new ArrayList<>();
        for (PromotionActivity activity : activityRepository.findAll()) {
            List<CouponDefinition> defs = couponDefinitionRepository.findByActivityId(activity.getActivityId());
            // OBS-006：无券活动也入表，发券/核销为 0，避免「有活动却不在 ROI」误解
            Set<Long> couponInstanceIds = new LinkedHashSet<>();
            long claimed = 0;
            long used = 0;
            for (CouponDefinition def : defs) {
                claimed += userCouponRepository.countByCouponDefId(def.getCouponDefId());
                used += userCouponRepository.countByCouponDefIdAndStatus(def.getCouponDefId(), "USED");
                for (UserCoupon uc : userCouponRepository.findByCouponDefId(def.getCouponDefId())) {
                    couponInstanceIds.add(uc.getCouponId());
                }
            }
            long orderCount = 0;
            long revenue = 0;
            long discount = 0;
            if (!couponInstanceIds.isEmpty()) {
                for (CabinetOrder o : orderRepository.findByCouponIdInAndCreatedAtAfter(couponInstanceIds, since)) {
                    orderCount++;
                    revenue += o.getTotalAmountCents();
                    discount += o.getCouponDiscountCents();
                }
            }
            double redeemRate = claimed > 0 ? (double) used / claimed : 0.0;
            out.add(new MarketingRoiRowDto(
                    activity.getActivityId(),
                    activity.getActivityName(),
                    activity.getActivityType(),
                    activity.getStatus(),
                    activity.getBudgetCents(),
                    activity.getUsedCents(),
                    claimed,
                    used,
                    orderCount,
                    revenue,
                    discount,
                    redeemRate
            ));
        }
        out.sort(Comparator.comparingLong(MarketingRoiRowDto::orderRevenueCents).reversed());
        return out;
    }
}
