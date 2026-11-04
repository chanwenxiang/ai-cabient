package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import com.aicabinet.trade.domain.PromotionActivity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P3-6：消费者 banners 混合口径——广告轮播位优先、活动横幅补位、全空静态兜底。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsumerMarketingBannersTest {

    @Mock private PromotionService promotionService;
    @Mock private AdCampaignService adCampaignService;
    @Mock private PromotionActivityMapper activityRepository;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private CouponService couponService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApiRateLimitService apiRateLimitService;
    @Mock private PromotionPlacementMapper placementRepository;
    @Mock private PromotionPricingService promotionPricingService;

    private ConsumerMarketingService service;

    @BeforeEach
    void setUp() {
        service = new ConsumerMarketingService(promotionService, adCampaignService, activityRepository,
                couponDefinitionRepository, userCouponRepository, couponService, distributedLockService,
                apiRateLimitService, placementRepository, promotionPricingService,
                "/pages/coupons/coupons");
    }

    @Test
    void banners_adFirst_activityFillsRest() {
        when(adCampaignService.listMiniProgramBanners(org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(new AdCampaignService.MiniProgramBanner(7L, 700L, "新品上市", "/pages/member/index")));
        when(promotionService.listCurrentlyRunning()).thenReturn(List.of());
        when(promotionService.listActive()).thenReturn(List.of());
        when(promotionService.listCurrentlyRunning()).thenReturn(List.of());

        // activeCampaigns 走 listCurrentlyRunning→空→listActive→空 ⇒ 活动横幅 0 条
        List<MarketingBannerDto> banners = service.banners();

        assertEquals(1, banners.size());
        assertEquals("/api/v2/media/ad-assets/700", banners.get(0).imageUrl());
        assertEquals(7L, banners.get(0).adCampaignId());
        assertEquals(700L, banners.get(0).assetId());
        assertNull(banners.get(0).campaignId(), "广告横幅不携带活动 id（不可领券）");
        assertEquals("/pages/member/index", banners.get(0).ctaPath());
    }

    @Test
    void banners_allEmpty_fallsBackToStaticCouponBanner() {
        when(adCampaignService.listMiniProgramBanners(org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());
        when(promotionService.listCurrentlyRunning()).thenReturn(List.of());
        when(promotionService.listActive()).thenReturn(List.of());

        List<MarketingBannerDto> banners = service.banners();

        assertEquals(1, banners.size());
        assertTrue(banners.get(0).title().contains("领券"));
        assertNull(banners.get(0).imageUrl(), "兜底横幅仍为文案卡，无图片");
    }


    // ── 批次 S：展示位打点的入参校验 ──────────────────────────────────────
    private static PromotionActivity activity(Long id, String status) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityId(id);
        a.setStatus(status);
        return a;
    }

    @Test
    void recordPlacementEvent_rejectsUnknownPosition() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordPlacementEvent(7L, "POPUP_WECHAT", 55L, null, null, "IMPRESSION"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("展示位"));
        verify(adCampaignService, never())
                .recordPlacementEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordPlacementEvent_requiresActivityOrCampaign() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordPlacementEvent(7L, "BANNER", null, null, null, "IMPRESSION"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(adCampaignService, never())
                .recordPlacementEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordPlacementEvent_rejectsMissingOrInactiveActivity() {
        // 默认 mock 返回空列表 ⇒ 活动不存在
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordPlacementEvent(7L, "POPUP_BEFORE_OPEN", 55L, null, null, "IMPRESSION"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        when(activityRepository.findAllById(List.of(56L))).thenReturn(List.of(activity(56L, "DRAFT")));
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> service.recordPlacementEvent(7L, "POPUP_BEFORE_OPEN", 56L, null, null, "IMPRESSION"));
        assertTrue(ex2.getReason().contains("活动不存在或已停用"));
    }

    @Test
    void recordPlacementEvent_upperCasesPositionAndDelegates() {
        when(activityRepository.findAllById(List.of(55L))).thenReturn(List.of(activity(55L, "ACTIVE")));
        service.recordPlacementEvent(7L, " popup_before_open ", 55L, null, null, "CLICK");
        verify(adCampaignService).recordPlacementEvent(
                7L, "POPUP_BEFORE_OPEN", 55L, null, null, "CLICK");
    }

    @Test
    void recordPlacementEvent_bannerWithCampaignOnlySkipsActivityLookup() {
        service.recordPlacementEvent(7L, "BANNER", null, 3L, 9L, "IMPRESSION");
        verify(adCampaignService).recordPlacementEvent(7L, "BANNER", null, 3L, 9L, "IMPRESSION");
        verify(activityRepository, never()).findAllById(any());
    }
}
