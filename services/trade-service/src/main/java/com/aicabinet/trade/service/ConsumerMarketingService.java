package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

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
import java.util.function.Supplier;

@Service
public class ConsumerMarketingService {
    private static final String DISCOUNT = "DISCOUNT";
    private static final String COUPONS_PAGE_PATH = "/pages/coupons/coupons";


    private static final Map<String, String> TYPE_LABELS = Map.of(
            DISCOUNT, "满减",
            "NEW_USER", "新客",
            "FLASH", "限时",
            "GIFT", "赠品"
    );

    private static final Map<String, String> TYPE_EMOJI = Map.of(
            DISCOUNT, "🧊",
            "NEW_USER", "🎉",
            "FLASH", "⚡",
            "GIFT", "🎁"
    );

    private static final String[] TONES = {"mint", "amber", "sky", "rose"};

    private final PromotionService promotionService;
    private final PromotionActivityMapper activityRepository;
    private final CouponDefinitionMapper couponDefinitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final CouponService couponService;
    private final DistributedLockService distributedLockService;

    public ConsumerMarketingService(PromotionService promotionService,
                                    PromotionActivityMapper activityRepository,
                                    CouponDefinitionMapper couponDefinitionRepository,
                                    UserCouponMapper userCouponRepository,
                                    CouponService couponService,
                                    DistributedLockService distributedLockService) {
        this.promotionService = promotionService;
        this.activityRepository = activityRepository;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponService = couponService;
        this.distributedLockService = distributedLockService;
    }

    public List<MarketingCampaignDto> activeCampaigns() {
        return activeCampaigns(null);
    }

    public List<MarketingCampaignDto> activeCampaigns(Long userId) {
        List<PromotionActivityDto> running = promotionService.listCurrentlyRunning();
        if (running.isEmpty()) {
            running = promotionService.listActive();
        }
        // POINTS 活动已下线（无领券能力），不进消费者「进行中」列表，避免展示「已下线」CTA。
        return running.stream()
                .filter(p -> p.activityType() == null || !"POINTS".equalsIgnoreCase(p.activityType()))
                .map(p -> toCampaign(p, userId))
                .toList();
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
                    "领券更优惠",
                    "满减与新客礼等你领取",
                    "mint",
                    "🎫",
                    null,
                    "/pages/coupons/coupons"
            ));
        }
        return banners;
    }

    @Transactional
    public CouponDto claimCampaign(Long userId, Long activityId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return runWithCampaignClaimLock(userId, activityId, () -> doClaimCampaign(userId, activityId));
    }

    private CouponDto doClaimCampaign(Long userId, Long activityId) {
        PromotionActivity activity = activityRepository.findByIdForUpdate(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(activity.getStatus())) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该活动类型已下线");
        }

        CouponDefinition def = resolveCouponDef(activityId);
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动优惠券已停用");
        }

        int limit = Math.max(1, activity.getUserLimit());
        long claimed = userCouponRepository.countByUserIdAndCouponDefId(userId, def.getCouponDefId());
        if (claimed >= limit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "您已领取过该活动优惠券");
        }
        return couponService.issueToUser(userId, def.getCouponDefId());
    }

    static String campaignClaimLockKey(long userId, long activityId) {
        return "marketing:claim:" + userId + ":" + activityId;
    }

    private <T> T runWithCampaignClaimLock(long userId, long activityId, Supplier<T> action) {
        String lockKey = campaignClaimLockKey(userId, activityId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "领券处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private CouponDefinition resolveCouponDef(Long activityId) {
        return couponDefinitionRepository.findByActivityId(activityId).stream()
                .filter(d -> CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(d.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动暂无可领优惠券"));
    }

    private MarketingCampaignDto toCampaign(PromotionActivityDto p, Long userId) {
        String type = p.activityType() != null ? p.activityType() : DISCOUNT;
        String ctaPath = COUPONS_PAGE_PATH;
        // 「去领券」易被理解成仅跳转券包；实际点击会发券，文案用「立即领取」。
        String ctaLabel = "立即领取";
        Boolean claimed = null;
        Boolean claimable = true;
        if (userId != null) {
            var defs = couponDefinitionRepository.findByActivityId(p.activityId());
            if (!defs.isEmpty()) {
                Long defId = defs.get(0).getCouponDefId();
                long count = userCouponRepository.countByUserIdAndCouponDefId(userId, defId);
                int limit = p.userLimit() > 0 ? p.userLimit() : 1;
                claimed = count >= limit;
                claimable = !claimed;
                if (Boolean.TRUE.equals(claimed)) {
                    ctaLabel = "查看券包";
                }
            } else {
                claimable = false;
                ctaLabel = "暂无可领";
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
