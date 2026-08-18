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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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

    public MarketingRoiService(PromotionActivityMapper activityRepository,
                               CouponDefinitionMapper couponDefinitionRepository,
                               UserCouponMapper userCouponRepository,
                               CabinetOrderMapper orderRepository) {
        this.activityRepository = activityRepository;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.orderRepository = orderRepository;
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
