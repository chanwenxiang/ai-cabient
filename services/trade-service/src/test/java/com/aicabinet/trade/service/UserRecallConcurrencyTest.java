package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRecallConcurrencyTest {

    @Mock private UserBehaviorAnalyticsService behaviorAnalyticsService;
    @Mock private CouponService couponService;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private NotificationService notificationService;
    @Mock private DistributedLockService distributedLockService;

    private UserRecallService service;

    @BeforeEach
    void setUp() {
        service = new UserRecallService(behaviorAnalyticsService, couponService,
                couponDefinitionRepository, notificationService, distributedLockService);
    }

    @Test
    void recall_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(UserRecallService.recallCouponLockKey(77L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recall(77L, 30, List.of(100L)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
