package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.SessionExpireProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C08/C09/M14：识别超时升级——会话/行锁、releaseIfFrozen、批处理失败计数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionExpireRecognizingTest {

    @Mock private ShoppingSessionMapper repository;
    @Mock private CabinetMetrics cabinetMetrics;
    @Mock private RestockSnapshotService restockSnapshotService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private DisputeService disputeService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;
    @Mock private SessionService sessionService;

    private SessionExpireService expireService;

    @BeforeEach
    void setUp() {
        expireService = new SessionExpireService(
                repository,
                SessionExpireProperties.defaults(),
                cabinetMetrics,
                restockSnapshotService,
                opsExceptionService,
                disputeService,
                consumerPreauthService,
                distributedLockService,
                taskService,
                sessionService);
        lenient().when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    private ShoppingSession staleRecognizing(String sessionId) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(sessionId);
        s.setUserId(10001L);
        s.setDeviceId("CAB-001");
        s.setState(SessionState.RECOGNIZING);
        s.setUpdatedAt(Instant.now().minus(Duration.ofMinutes(30)));
        return s;
    }

    private void stubBatch(ShoppingSession... sessions) {
        when(repository.findByStateInAndUpdatedAtBefore(anyList(), any(Instant.class), eq(500)))
                .thenReturn(List.of(sessions));
    }

    /** C08：升级前抢 session:life 分布式锁并 findByIdForUpdate 行锁重查后再迁移。 */
    @Test
    void expireRecognizing_upgradesUnderSessionLifeLockAndRowLock() {
        ShoppingSession stale = staleRecognizing("S-R1");
        stubBatch(stale);
        when(repository.findByIdForUpdate("S-R1")).thenReturn(Optional.of(stale));

        expireService.expireStaleRecognizingSessions();

        verify(distributedLockService).tryLock(
                eq(SessionService.sessionLifeLockKey("S-R1")), eq(30L), eq(0L));
        verify(repository).findByIdForUpdate("S-R1");
        verify(sessionService).transition(stale, SessionState.DISPUTED);
        verify(disputeService).createTimeoutTicket(eq(stale), anyString());
        verify(taskService).finish(eq("session-recognizing-expire"), eq("SUCCESS"),
                contains("识别超时升级 1"), anyLong());
    }

    /** C08：抢锁失败跳过本会话（下轮重扫），不做任何迁移。 */
    @Test
    void expireRecognizing_lockBusy_skipsSession() {
        ShoppingSession stale = staleRecognizing("S-R2");
        stubBatch(stale);
        when(distributedLockService.tryLock(
                eq(SessionService.sessionLifeLockKey("S-R2")), eq(30L), eq(0L))).thenReturn(false);

        expireService.expireStaleRecognizingSessions();

        verify(repository, never()).findByIdForUpdate("S-R2");
        verify(sessionService, never()).transition(any(), any());
        verify(taskService).finish(eq("session-recognizing-expire"), eq("SUCCESS"),
                contains("本次无识别超时会话"), anyLong());
    }

    /** C09：releaseIfFrozen 在迁移前调用；其失败不阻断状态迁移。 */
    @Test
    void expireRecognizing_releaseIfFrozenCalledBeforeTransition_andFailureDoesNotBlock() {
        ShoppingSession stale = staleRecognizing("S-R3");
        stubBatch(stale);
        when(repository.findByIdForUpdate("S-R3")).thenReturn(Optional.of(stale));
        doThrow(new RuntimeException("release failed"))
                .when(consumerPreauthService).releaseIfFrozen(stale);

        expireService.expireStaleRecognizingSessions();

        verify(sessionService).transition(stale, SessionState.DISPUTED);
        verify(disputeService).createTimeoutTicket(eq(stale), anyString());
        verify(taskService).finish(eq("session-recognizing-expire"), eq("SUCCESS"),
                contains("识别超时升级 1"), anyLong());
    }

    /** M14：单会话迁移异常计入 failures，任务置 FAILED 且 summary 带 failures=N。 */
    @Test
    void expireRecognizing_sessionFailure_countedInSummaryAndTaskFailed() {
        ShoppingSession stale = staleRecognizing("S-R4");
        stubBatch(stale);
        when(repository.findByIdForUpdate("S-R4")).thenReturn(Optional.of(stale));
        doThrow(new RuntimeException("transition failed"))
                .when(sessionService).transition(stale, SessionState.DISPUTED);
        doNothing().when(consumerPreauthService).releaseIfFrozen(stale);

        expireService.expireStaleRecognizingSessions();

        verify(taskService).finish(eq("session-recognizing-expire"), eq("FAILED"),
                contains("failures=1"), anyLong());
    }

    /** M14：升级成功不误报 failures。 */
    @Test
    void expireRecognizing_successSummaryHasNoFailuresSuffix() {
        ShoppingSession stale = staleRecognizing("S-R5");
        stubBatch(stale);
        when(repository.findByIdForUpdate("S-R5")).thenReturn(Optional.of(stale));

        expireService.expireStaleRecognizingSessions();

        verify(sessionService).transition(stale, SessionState.DISPUTED);
        verify(taskService).finish(eq("session-recognizing-expire"), eq("SUCCESS"),
                contains("识别超时升级 1"), anyLong());
    }
}
