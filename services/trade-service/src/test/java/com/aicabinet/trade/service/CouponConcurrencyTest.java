package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponConcurrencyTest {

    @Mock private CouponDefinitionMapper definitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private PromotionService promotionService;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(
                definitionRepository, userCouponRepository, userInfoRepository, orderRepository,
                orderLineRepository, distributedLockService, promotionService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(couponService, "self", couponService);
    }

    @Test
    void useCoupon_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(CouponService.couponUseLockKey(99L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 99L, "O-1", "CAB-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void useCoupon_whenCouponNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(CouponService.couponUseLockKey(99L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(userCouponRepository.findByIdForUpdate(99L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 99L, "O-1", "CAB-1"));

        verify(distributedLockService).unlock(CouponService.couponUseLockKey(99L));
    }
}
