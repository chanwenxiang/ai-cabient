package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
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
    @Mock private PromotionActivityMapper activityRepository;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private CouponService couponService;
    @Mock private DistributedLockService distributedLockService;

    private ConsumerMarketingService service;

    @BeforeEach
    void setUp() {
        service = new ConsumerMarketingService(promotionService, activityRepository,
                couponDefinitionRepository, userCouponRepository, couponService, distributedLockService,
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
}
