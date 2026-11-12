package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MarketingRoiRowDto;
import com.aicabinet.common.dto.PlacementEffectRowDto;
import com.aicabinet.trade.domain.AdCampaign;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
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
import java.util.stream.Collectors;
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

    /**
     * 活动 ROI 列表（发券 → 核销 → 带动营收）。
     *
     * <p>R20 P2-15：原实现是典型的三层 N+1——每个活动查一次券定义、每个券定义查三次券实例
     * （总数/USED/全部）、每个活动再查一次订单；一个挂 5 种券的活动就是 16 次查询，活动数十个
     * 就是几百次往返。现在固定为 **5 次**查询：活动清单、券定义（一次 IN）、券实例聚合（一条
     * group-by）、订单聚合（一条 join+group-by），与活动数无关。
     *
     * <p>口径未变（逐项对齐旧实现）：领取数/核销数按「活动下全部券定义求和」；营收与券面额
     * 按窗口内 {@code created_at >= since}、**不筛订单状态**（含退款单的毛额，旧口径如此）；
     * 核销率 = 已使用 / 已领取（领取 0 时为 0）。无券活动依旧入表（OBS-006）。
     */
    @Transactional(readOnly = true)
    public List<MarketingRoiRowDto> list(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        List<PromotionActivity> activities = activityRepository.findAllForRoi();
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = activities.stream().map(PromotionActivity::getActivityId).toList();

        Map<Long, List<CouponDefinition>> defsByActivity = couponDefinitionRepository
                .findByActivityIdIn(activityIds).stream()
                .filter(def -> def.getActivityId() != null)
                .collect(Collectors.groupingBy(CouponDefinition::getActivityId));
        List<Long> defIds = defsByActivity.values().stream()
                .flatMap(List::stream)
                .map(CouponDefinition::getCouponDefId)
                .toList();

        // defId -> {claimed, used}
        Map<Long, long[]> couponAgg = new HashMap<>();
        if (!defIds.isEmpty()) {
            for (Map<String, Object> r : userCouponRepository.countGroupByCouponDefIds(defIds)) {
                Long defId = asLongOrNull(r.get("coupon_def_id"));
                if (defId == null) {
                    continue;
                }
                couponAgg.put(defId, new long[]{asLong(r.get("claimed")), asLong(r.get("used"))});
            }
        }

        // activityId -> {orderCount, revenueCents, discountCents}
        Map<Long, long[]> orderAgg = new HashMap<>();
        for (Map<String, Object> r : orderRepository.sumCouponOrdersByActivitySince(activityIds, since)) {
            Long activityId = asLongOrNull(r.get("activity_id"));
            if (activityId == null) {
                continue;
            }
            orderAgg.put(activityId, new long[]{
                    asLong(r.get("order_count")),
                    asLong(r.get("revenue_cents")),
                    asLong(r.get("discount_cents"))});
        }

        List<MarketingRoiRowDto> out = new ArrayList<>();
        for (PromotionActivity activity : activities) {
            long claimed = 0;
            long used = 0;
            for (CouponDefinition def : defsByActivity.getOrDefault(activity.getActivityId(), List.of())) {
                long[] agg = couponAgg.getOrDefault(def.getCouponDefId(), new long[]{0, 0});
                claimed += agg[0];
                used += agg[1];
            }
            long[] orderAggOfActivity = orderAgg.getOrDefault(activity.getActivityId(), new long[]{0, 0, 0});
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
                    orderAggOfActivity[0],
                    orderAggOfActivity[1],
                    orderAggOfActivity[2],
                    redeemRate
            ));
        }
        out.sort(Comparator.comparingLong(MarketingRoiRowDto::orderRevenueCents).reversed());
        return out;
    }

    /** 聚合结果里的 id 列：不可解析/为空时返回 null，由调用方跳过该行（宁缺不错位）。 */
    private static Long asLongOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
