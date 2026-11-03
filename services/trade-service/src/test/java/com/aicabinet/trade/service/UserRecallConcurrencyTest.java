package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UserRecallResult;
import com.aicabinet.trade.domain.CouponDefinition;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                UserRecallService.recallWindowKey(77L), 60L, 0L)).thenReturn(true);
        when(distributedLockService.tryLock(
                UserRecallService.recallCouponLockKey(77L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recall(9001L, null, 77L, 30, List.of(100L)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void recall_requestIdReplay_rejectsWithoutReissuing() {
        when(distributedLockService.tryLock(
                UserRecallService.recallRequestKey("req-1"), 600L, 0L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recall(9001L, "req-1", 77L, 30, List.of(100L)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(couponService, never()).batchIssue(any(), anyLong(), anyList());
    }

    @Test
    void recall_noRequestId_windowBusy_rejectsWithoutReissuing() {
        when(distributedLockService.tryLock(
                UserRecallService.recallWindowKey(77L), 60L, 0L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recall(9001L, null, 77L, 30, List.of(100L)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(couponService, never()).batchIssue(any(), anyLong(), anyList());
    }

    @Test
    void recall_success_issuesCouponsAndNotifies() {
        when(distributedLockService.tryLock(
                UserRecallService.recallWindowKey(77L), 60L, 0L)).thenReturn(true);
        when(distributedLockService.tryLock(
                UserRecallService.recallCouponLockKey(77L), 60L, 5L)).thenReturn(true);
        CouponDefinition def = new CouponDefinition();
        def.setCouponDefId(77L);
        def.setCouponName("召回券");
        def.setStatus("ACTIVE");
        when(couponDefinitionRepository.findByIdForUpdate(77L)).thenReturn(java.util.Optional.of(def));
        when(couponService.batchIssue(eq(9001L), eq(77L), eq(List.of(100L))))
                .thenReturn(List.of());

        UserRecallResult result = service.recall(9001L, null, 77L, 30, List.of(100L));

        assertEquals(1, result.issuedCount());
        verify(notificationService).notifyConsumer(eq(100L), eq("user_recall"),
                any(), eq("RECALL"), eq(null));
    }
}
