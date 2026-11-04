package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerMarketingConcurrencyTest {

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
    void claimCampaign_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                ConsumerMarketingService.campaignClaimLockKey(500L, 42L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimCampaign(500L, 42L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ── MA-154：领券的「按人计量」闸（此前只钉过「锁忙」一条）────────────────────
    //
    // 这条口没有路径主体可伪造，风险形态是「额度算错人」：count 一旦改成按 activityId 计，
    // 第一个人领完后全站就都领不到了——负向用例（他人重放）在这里没有意义，
    // 真正要钉的是「计数按调用者取 + 只发给调用者」。

    @Test
    void claimCampaign_whenCallerAlreadyAtLimit_rejectsAndIssuesNothing() {
        when(distributedLockService.tryLock(
                ConsumerMarketingService.campaignClaimLockKey(500L, 42L), 60L, 5L)).thenReturn(true);
        when(activityRepository.findByIdForUpdate(42L)).thenReturn(java.util.Optional.of(activeActivity()));
        when(couponDefinitionRepository.findByActivityId(42L)).thenReturn(java.util.List.of(activeDef(777L)));
        when(userCouponRepository.countByUserIdAndCouponDefId(500L, 777L)).thenReturn(1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claimCampaign(500L, 42L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), "已达本人限领数必须拒");
        org.mockito.Mockito.verify(couponService, org.mockito.Mockito.never())
                .issueToUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void claimCampaign_countsPerCaller_andIssuesToCallerOnly() {
        // 正向对照：换一个人（501）额度是干净的 ⇒ 能领，且发给他自己而不是上一个领过的人
        when(distributedLockService.tryLock(
                ConsumerMarketingService.campaignClaimLockKey(501L, 42L), 60L, 5L)).thenReturn(true);
        when(activityRepository.findByIdForUpdate(42L)).thenReturn(java.util.Optional.of(activeActivity()));
        when(couponDefinitionRepository.findByActivityId(42L)).thenReturn(java.util.List.of(activeDef(777L)));
        when(userCouponRepository.countByUserIdAndCouponDefId(501L, 777L)).thenReturn(0L);

        service.claimCampaign(501L, 42L);

        org.mockito.Mockito.verify(couponService).issueToUser(501L, 777L);
    }

    private static PromotionActivity activeActivity() {
        PromotionActivity a = new PromotionActivity();
        a.setActivityId(42L);
        a.setStatus("ACTIVE");
        a.setUserLimit(1);
        return a;
    }

    private static CouponDefinition activeDef(Long couponDefId) {
        CouponDefinition d = new CouponDefinition();
        d.setCouponDefId(couponDefId);
        d.setStatus("ACTIVE");
        return d;
    }
}
