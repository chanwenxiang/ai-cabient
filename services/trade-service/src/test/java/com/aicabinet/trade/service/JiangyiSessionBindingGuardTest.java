package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-060（审计 B-4 P1）：将邑设备上报面 open-failed / recognize-timeout 转发支路
 * 原不带 deviceId、无归属校验——任一持合法设备 JWT 者可用他人 orderNo 腰斩受害人会话
 * （FAILED+释预授权 / DISPUTED）。修复：内部转发链透传 token deviceId，
 * trade 侧提供 deviceId 时必须与会话绑定设备一致，不一致一律跳过处置（对齐
 * door-event/edge-results 的 DEVICE_MISMATCH 口径）；deviceId=null 保留旧调用方
 * （device-service ACK 回调、trade 自有链）语义不变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JiangyiSessionBindingGuardTest {

    private static final String SESSION = "S-060";
    private static final String OWNER_DEVICE = "JY-OWNER";
    private static final String FOREIGN_DEVICE = "JY-FOREIGN";

    @Mock private ShoppingSessionMapper repository;
    @Mock private SessionService sessionService;
    @Mock private DisputeService disputeService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private JiangyiDeviceDirectory jiangyiDeviceDirectory;

    private SessionOpenService openService;
    private JiangyiRecognitionTimeoutService timeoutService;

    @BeforeEach
    void setUp() {
        openService = new SessionOpenService(repository, null, null, null,
                consumerPreauthService, null, null, sessionService,
                distributedLockService, opsAlertDispatcher, null);
        timeoutService = new JiangyiRecognitionTimeoutService(repository, sessionService,
                disputeService, consumerPreauthService, distributedLockService, opsExceptionService,
                jiangyiDeviceDirectory);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    private static ShoppingSession session(SessionState state) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(SESSION);
        s.setDeviceId(OWNER_DEVICE);
        s.setUserId(200_000_001L);
        s.setState(state);
        return s;
    }

    // ── open-failed ──────────────────────────────────────────────────────

    @Test
    void markOpenDoorFailed_foreignDevice_noTransitionNoRelease() {
        when(repository.findByIdForUpdate(SESSION))
                .thenReturn(Optional.of(session(SessionState.OPENING)));

        openService.markOpenDoorFailed(SESSION, "他人设备伪造开锁失败", FOREIGN_DEVICE);

        verify(sessionService, never()).transition(any(), any());
        verify(consumerPreauthService, never()).releaseIfFrozen(any());
    }

    @Test
    void markOpenDoorFailed_matchingDevice_stillTransitions() {
        when(repository.findByIdForUpdate(SESSION))
                .thenReturn(Optional.of(session(SessionState.OPENING)));

        openService.markOpenDoorFailed(SESSION, "开锁失败", OWNER_DEVICE);

        verify(sessionService).transition(any(ShoppingSession.class), eq(SessionState.FAILED));
    }

    @Test
    void markOpenDoorFailed_nullDevice_keepsLegacySemantics() {
        when(repository.findByIdForUpdate(SESSION))
                .thenReturn(Optional.of(session(SessionState.OPENING)));

        openService.markOpenDoorFailed(SESSION, "开门指令下发失败", null);

        verify(sessionService).transition(any(ShoppingSession.class), eq(SessionState.FAILED));
    }

    // ── recognize-timeout ────────────────────────────────────────────────

    private static final String NO_REPORT = JiangyiRecognitionTimeoutService.CAUSE_NO_REPORT;

    @Test
    void markRecognitionTimeout_foreignDevice_returnsFalseAndUntouched() {
        when(repository.findByIdForUpdate(SESSION))
                .thenReturn(Optional.of(session(SessionState.RECOGNIZING)));

        assertFalse(timeoutService.markRecognitionTimeout(SESSION, FOREIGN_DEVICE, NO_REPORT, null));

        verify(sessionService, never()).transition(any(), any());
        verify(consumerPreauthService, never()).releaseIfFrozen(any());
    }

    @Test
    void markRecognitionTimeout_matchingDevice_stillEscalates() {
        when(repository.findByIdForUpdate(SESSION))
                .thenReturn(Optional.of(session(SessionState.RECOGNIZING)));

        timeoutService.markRecognitionTimeout(SESSION, OWNER_DEVICE, NO_REPORT, null);

        verify(consumerPreauthService).releaseIfFrozen(any());
    }

    // ── 模式一无关门上报：SHOPPING 会话由本服务合成关门（2026-10-21 真机四单实测）──

    @Test
    void markRecognitionTimeout_shoppingJiangyiSession_synthesizesCloseAndEscalates() {
        ShoppingSession shopping = session(SessionState.SHOPPING);
        when(repository.findByIdForUpdate(SESSION)).thenReturn(Optional.of(shopping));
        when(jiangyiDeviceDirectory.isJiangyi(OWNER_DEVICE)).thenReturn(true);
        stubRealTransition();

        assertTrue(timeoutService.markRecognitionTimeout(SESSION, OWNER_DEVICE, NO_REPORT, null));

        assertNotNull(shopping.getCloseTime(), "合成关门必须留 close_time，否则记录仍像没关门");
        assertEquals(SessionState.DISPUTED, shopping.getState());
        verify(disputeService).createTimeoutTicket(eq(shopping), anyString());
    }

    @Test
    void markRecognitionTimeout_shoppingNonJiangyiSession_returnsFalseUntouched() {
        ShoppingSession shopping = session(SessionState.SHOPPING);
        when(repository.findByIdForUpdate(SESSION)).thenReturn(Optional.of(shopping));
        when(jiangyiDeviceDirectory.isJiangyi(OWNER_DEVICE)).thenReturn(false);
        stubRealTransition();

        assertFalse(timeoutService.markRecognitionTimeout(SESSION, OWNER_DEVICE, NO_REPORT, null));

        assertEquals(SessionState.SHOPPING, shopping.getState());
        assertNull(shopping.getCloseTime());
        verify(consumerPreauthService, never()).releaseIfFrozen(any());
    }

    /** transition 是包私有且真实会改状态+落库；mock 默认 no-op 会让本服务的二次状态判定失真。 */
    private void stubRealTransition() {
        doAnswer(inv -> {
            ShoppingSession s = inv.getArgument(0);
            s.setState(inv.getArgument(1));
            return null;
        }).when(sessionService).transition(any(), any());
    }
}
