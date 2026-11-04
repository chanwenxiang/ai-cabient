package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.common.dto.MarketingCampaignDto;
import com.aicabinet.common.dto.MarketingPopupDto;
import com.aicabinet.common.dto.PromotionActivityDto;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.PromotionPlacement;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String MARKETING_PAGE = "/pages/marketing/index";
    private static final Map<String, String> TYPE_LABELS = Map.of(
            DISCOUNT, "满减",
            "NEW_USER", "新客",
            "FLASH", "限时",
            "GIFT", "赠品",
            "SKU_REDUCE", "商品立减",
            "SKU_DISCOUNT", "商品折扣",
            "SKU_FIXED_PRICE", "一口价"
    );

    private static final Map<String, String> TYPE_EMOJI = Map.of(
            DISCOUNT, "🧊",
            "NEW_USER", "🎉",
            "FLASH", "⚡",
            "GIFT", "🎁",
            "SKU_REDUCE", "🏷",
            "SKU_DISCOUNT", "🏷",
            "SKU_FIXED_PRICE", "🏷"
    );

    private static final String[] TONES = {"mint", "amber", "sky", "rose"};

    private final PromotionService promotionService;
    private final AdCampaignService adCampaignService;
    private final PromotionActivityMapper activityRepository;
    private final CouponDefinitionMapper couponDefinitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final CouponService couponService;
    private final DistributedLockService distributedLockService;
    private final ApiRateLimitService apiRateLimitService;
    private final PromotionPlacementMapper placementRepository;
    private final PromotionPricingService promotionPricingService;
    private final String couponsPagePath;

    public ConsumerMarketingService(PromotionService promotionService,
                                    AdCampaignService adCampaignService,
                                    PromotionActivityMapper activityRepository,
                                    CouponDefinitionMapper couponDefinitionRepository,
                                    UserCouponMapper userCouponRepository,
                                    CouponService couponService,
                                    DistributedLockService distributedLockService,
                                    ApiRateLimitService apiRateLimitService,
                                    PromotionPlacementMapper placementRepository,
                                    PromotionPricingService promotionPricingService,
                                    @Value("${aicabinet.consumer.coupons-page-path:/pages/coupons/coupons}") String couponsPagePath) {
        this.promotionService = promotionService;
        this.adCampaignService = adCampaignService;
        this.activityRepository = activityRepository;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponService = couponService;
        this.distributedLockService = distributedLockService;
        this.apiRateLimitService = apiRateLimitService;
        this.placementRepository = placementRepository;
        this.promotionPricingService = promotionPricingService;
        this.couponsPagePath = couponsPagePath;
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
        return banners(null);
    }

    /** P3-6：deviceId 可选——SPECIFIC 范围的小程序广告仅在对应柜机上下文返回。 */
    public List<MarketingBannerDto> banners(String deviceId) {
        List<MarketingBannerDto> banners = new ArrayList<>();
        // CB-082：活动展示位最优先（运营为活动配的 banner 是真图，点击进活动页）
        banners.addAll(activityBanners(deviceId, 5));
        // P3-6：广告轮播位（MINI_PROGRAM 渠道投放，真图+深链；范围按 device_scope 过滤）
        for (AdCampaignService.MiniProgramBanner ad : adCampaignService.listMiniProgramBanners(5, deviceId)) {
            if (banners.size() >= 5) {
                break;
            }
            banners.add(new MarketingBannerDto(
                    ad.campaignId(),
                    ad.title(),
                    null,
                    "ad",
                    null,
                    null,
                    ad.linkUrl(),
                    "/api/v2/media/ad-assets/" + ad.assetId(),
                    ad.campaignId(),
                    ad.assetId()
            ));
        }
        // 活动横幅补位（剩余位置）；SKU 级活动已由展示位 banner 呈现，这里不重复
        int i = 0;
        for (MarketingCampaignDto c : activeCampaigns()) {
            if (banners.size() >= 5) break;
            if (PromotionActivityValidator.isSkuActivity(c.type())) continue;
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
        }
        if (banners.isEmpty()) {
            banners.add(new MarketingBannerDto(
                    0L,
                    "领券更优惠",
                    "满减与新客礼等你领取",
                    "mint",
                    "🎫",
                    null,
                    couponsPagePath
            ));
        }
        return banners;
    }

    /** 活动 banner（CB-082）：按企业/柜机范围取生效中的展示位，标题用活动名，点击进活动页。 */
    private List<MarketingBannerDto> activityBanners(String deviceId, int limit) {
        String merchantId = promotionPricingService.resolveMerchantId(deviceId);
        List<PromotionPlacement> placements =
                placementRepository.findActivePlacements(deviceId, merchantId, PromotionPlacementService.BANNER);
        if (placements.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = placements.stream().map(PromotionPlacement::getActivityId).distinct().toList();
        Map<Long, PromotionActivity> activities = activityRepository.findAllById(activityIds).stream()
                .collect(java.util.stream.Collectors.toMap(PromotionActivity::getActivityId, a -> a));
        List<MarketingBannerDto> out = new ArrayList<>();
        for (PromotionPlacement p : placements) {
            if (out.size() >= limit) {
                break;
            }
            PromotionActivity a = activities.get(p.getActivityId());
            if (a == null) {
                continue;
            }
            out.add(new MarketingBannerDto(
                    a.getActivityId(),
                    a.getActivityName(),
                    a.getDescription() != null && !a.getDescription().isBlank()
                            ? a.getDescription() : "限时活动价",
                    "mint",
                    "🏷",
                    a.getActivityId(),
                    MARKETING_PAGE,
                    "/api/v2/media/ad-assets/" + p.getAssetId(),
                    null,
                    p.getAssetId()
            ));
        }
        return out;
    }

    /**
     * 展示位打点（批次 S）：位置必须在字典 {@code promotion_placement_position} 集合内，
     * 活动必须存在且在售——否则 400（不静默接受，否则报表里会出现无人能看到的"幽灵位"）。
     */
    public void recordPlacementEvent(Long userId, String position, Long activityId, Long campaignId,
                                     Long assetId, String eventType) {
        String pos = position == null ? "" : position.trim().toUpperCase();
        if (!PromotionPlacementService.isKnownPosition(pos)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的展示位: " + position);
        }
        if (activityId == null && campaignId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activityId 与 campaignId 至少给一个");
        }
        if (activityId != null
                && activityRepository.findAllById(List.of(activityId)).stream()
                        .noneMatch(a -> CabinetConstants.PROMOTION_STATUS_ACTIVE
                                .equalsIgnoreCase(a.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动不存在或已停用");
        }
        adCampaignService.recordPlacementEvent(userId, pos, activityId, campaignId, assetId, eventType);
    }

    /** 活动弹窗（CB-082）：position ∈ POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE，互斥由配置端保证。 */
    public List<MarketingPopupDto> popups(String deviceId, String position) {
        String pos = position == null ? "" : position.trim().toUpperCase();
        if (!PromotionPlacementService.POPUP_BEFORE_OPEN.equals(pos)
                && !PromotionPlacementService.POPUP_AFTER_CLOSE.equals(pos)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的弹窗位置: " + position);
        }
        String merchantId = promotionPricingService.resolveMerchantId(deviceId);
        List<PromotionPlacement> placements =
                placementRepository.findActivePlacements(deviceId, merchantId, pos);
        if (placements.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = placements.stream().map(PromotionPlacement::getActivityId).distinct().toList();
        Map<Long, PromotionActivity> activities = activityRepository.findAllById(activityIds).stream()
                .collect(java.util.stream.Collectors.toMap(PromotionActivity::getActivityId, a -> a));
        List<MarketingPopupDto> out = new ArrayList<>();
        for (PromotionPlacement p : placements) {
            PromotionActivity a = activities.get(p.getActivityId());
            if (a == null) {
                continue;
            }
            out.add(new MarketingPopupDto(
                    a.getActivityId(),
                    a.getActivityName(),
                    a.getDescription(),
                    pos,
                    "/api/v2/media/ad-assets/" + p.getAssetId(),
                    MARKETING_PAGE,
                    p.getImageWidth(),
                    p.getImageHeight()
            ));
        }
        return out;
    }

    @Transactional
    public CouponDto claimCampaign(Long userId, Long activityId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        apiRateLimitService.assertCouponClaimAllowed(userId);
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
        // CB-082：SKU 级活动不领券，直接引导到柜选购（活动价在商品列表按命中活动展示）
        if (PromotionActivityValidator.isSkuActivity(type)) {
            return new MarketingCampaignDto(
                    p.activityId(), p.activityName(), p.description(), type,
                    TYPE_LABELS.getOrDefault(type, "活动"),
                    TONES[Math.floorMod(p.activityId() != null ? p.activityId().intValue() : 0, TONES.length)],
                    TYPE_EMOJI.getOrDefault(type, "🏷"),
                    p.startTime(), p.endTime(), p.status(),
                    "到柜选购", "/pages/index/index", false, false);
        }
        String ctaPath = couponsPagePath;
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
