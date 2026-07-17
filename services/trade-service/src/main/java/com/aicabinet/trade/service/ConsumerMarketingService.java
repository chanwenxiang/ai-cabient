package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.common.dto.MarketingCampaignDto;
import com.aicabinet.common.dto.PromotionActivityDto;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ConsumerMarketingService {

    private static final Map<String, String> TYPE_LABELS = Map.of(
            "DISCOUNT", "满减",
            "NEW_USER", "新客",
            "POINTS", "积分",
            "FLASH", "限时",
            "GIFT", "赠品"
    );

    private static final Map<String, String> TYPE_EMOJI = Map.of(
            "DISCOUNT", "🧊",
            "NEW_USER", "🎉",
            "POINTS", "⭐",
            "FLASH", "⚡",
            "GIFT", "🎁"
    );

    private static final String[] TONES = {"mint", "amber", "sky", "rose"};

    private final PromotionService promotionService;
    private final PromotionActivityMapper activityRepository;
    private final CouponDefinitionMapper couponDefinitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final CouponService couponService;

    public ConsumerMarketingService(PromotionService promotionService,
                                    PromotionActivityMapper activityRepository,
                                    CouponDefinitionMapper couponDefinitionRepository,
                                    UserCouponMapper userCouponRepository,
                                    CouponService couponService) {
        this.promotionService = promotionService;
        this.activityRepository = activityRepository;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponService = couponService;
    }

    public List<MarketingCampaignDto> activeCampaigns() {
        return activeCampaigns(null);
    }

    public List<MarketingCampaignDto> activeCampaigns(Long userId) {
        List<PromotionActivityDto> running = promotionService.listCurrentlyRunning();
        if (running.isEmpty()) {
            running = promotionService.listActive();
        }
        return running.stream().map(p -> toCampaign(p, userId)).toList();
    }

    public List<MarketingBannerDto> banners() {
        List<MarketingCampaignDto> campaigns = activeCampaigns();
        List<MarketingBannerDto> banners = new ArrayList<>();
        int i = 0;
        for (MarketingCampaignDto c : campaigns) {
            banners.add(new MarketingBannerDto(
                    c.id(),
                    c.title(),
                    c.description() != null ? c.description() : c.typeLabel(),
                    TONES[i % TONES.length],
                    c.coverEmoji(),
                    c.id(),
                    c.ctaPath()
            ));
            i++;
            if (banners.size() >= 5) break;
        }
        if (banners.isEmpty()) {
            banners.add(new MarketingBannerDto(
                    0L,
                    "积分兑好礼",
                    "100 积分起兑优惠券",
                    "mint",
                    "⭐",
                    null,
                    "/pages/member/exchange"
            ));
        }
        return banners;
    }

    @Transactional
    public CouponDto claimCampaign(Long userId, Long activityId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        PromotionActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
        if (!"ACTIVE".equalsIgnoreCase(activity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动未开始或已结束");
        }
        Instant now = Instant.now();
        if (activity.getStartTime() != null && now.isBefore(activity.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动尚未开始");
        }
        if (activity.getEndTime() != null && now.isAfter(activity.getEndTime())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动已结束");
        }
        if ("POINTS".equalsIgnoreCase(activity.getActivityType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请前往积分商城兑换");
        }

        CouponDefinition def = resolveCouponDef(activityId);
        if (!"ACTIVE".equalsIgnoreCase(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动优惠券已停用");
        }

        int limit = Math.max(1, activity.getUserLimit());
        long claimed = userCouponRepository.countByUserIdAndCouponDefId(userId, def.getCouponDefId());
        if (claimed >= limit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "您已领取过该活动优惠券");
        }
        return couponService.issueToUser(userId, def.getCouponDefId());
    }

    private CouponDefinition resolveCouponDef(Long activityId) {
        return couponDefinitionRepository.findByActivityId(activityId).stream()
                .filter(d -> "ACTIVE".equalsIgnoreCase(d.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动暂无可领优惠券"));
    }

    private MarketingCampaignDto toCampaign(PromotionActivityDto p, Long userId) {
        String type = p.activityType() != null ? p.activityType() : "DISCOUNT";
        boolean points = "POINTS".equalsIgnoreCase(type);
        String ctaPath = points ? "/pages/member/exchange" : "/pages/coupons/coupons";
        String ctaLabel = points ? "去兑换" : "去领券";
        Boolean claimed = null;
        Boolean claimable = null;
        if (!points && userId != null) {
            var defs = couponDefinitionRepository.findByActivityId(p.activityId());
            if (!defs.isEmpty()) {
                Long defId = defs.get(0).getCouponDefId();
                long count = userCouponRepository.countByUserIdAndCouponDefId(userId, defId);
                int limit = p.userLimit() > 0 ? p.userLimit() : 1;
                claimed = count >= limit;
                claimable = !claimed;
                if (Boolean.TRUE.equals(claimed)) {
                    ctaLabel = "已领取";
                }
            } else {
                claimable = false;
            }
        }
        return new MarketingCampaignDto(
                p.activityId(),
                p.activityName(),
                p.description(),
                type,
                TYPE_LABELS.getOrDefault(type, "活动"),
                TONES[Math.floorMod(p.activityId() != null ? p.activityId().intValue() : 0, TONES.length)],
                TYPE_EMOJI.getOrDefault(type, "🏷"),
                p.startTime(),
                p.endTime(),
                p.status(),
                ctaLabel,
                ctaPath,
                claimed,
                claimable
        );
    }
}
