package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话创建的**幂等键跨人复用**闸（MA-154 第二批）。
 *
 * <p>{@code POST /api/v2/sessions} 没有路径 id，越权面藏在 body 的 {@code idempotencyKey}：
 * 拿别人的键重放，服务端既不能给他开一台新柜（那是别人的会话），也不能把别人的会话回给他。
 * 第一轮核过 {@code SessionService#validateIdempotentReplay} 有这道闸，但全仓**一条用例都没有**。
 *
 * <p>判据不止「抛 409」：还断言**没有去抢开门设备锁**——真抢了就是给别人的柜机发了开门指令，
 * 那才是这条口最贵的后果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionIdempotentReplayTest {

    private static final Long OWNER = 2002L;
    private static final Long INTRUDER = 1001L;

    @Mock private ShoppingSessionMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                repository, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, distributedLockService, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private ShoppingSession existing(String sessionId, Long userId, String deviceId) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(sessionId);
        s.setUserId(userId);
        s.setDeviceId(deviceId);
        s.setIdempotencyKey("K-REPLAY");
        s.setState(SessionState.SHOPPING);
        return s;
    }

    @Test
    void createSession_replayingForeignIdempotencyKey_conflictsAndNeverOpensDevice() {
        when(repository.findByIdempotencyKey("K-REPLAY"))
                .thenReturn(Optional.of(existing("S-FOREIGN", OWNER, "CAB-001")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSession(INTRUDER, new CreateSessionRequest("CAB-001", "K-REPLAY")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), "别人的幂等键必须拒，不能当成自己的续放");
        verify(distributedLockService, never()).tryLock(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any(ShoppingSession.class));
    }

    @Test
    void createSession_replayingSameUsersKeyOnOtherDevice_alsoConflicts() {
        // 同一个人但换了柜机：键已被「本人 + 另一台柜」用过，同样必须拒——
        // 否则一次网络重试就能把用户的会话悄悄挪到另一台柜上开门
        when(repository.findByIdempotencyKey("K-REPLAY"))
                .thenReturn(Optional.of(existing("S-MINE", INTRUDER, "CAB-001")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSession(INTRUDER, new CreateSessionRequest("CAB-999", "K-REPLAY")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(distributedLockService, never()).tryLock(anyString(), anyLong(), anyLong());
    }

    @Test
    void createSession_ownerReplaysOwnSession_returnsExistingWithoutNewOpen() {
        // 正向对照：本人 + 同柜机重放 ⇒ 原样返回既有会话，不开新柜、不落新行
        when(repository.findByIdempotencyKey("K-REPLAY"))
                .thenReturn(Optional.of(existing("S-MINE", INTRUDER, "CAB-001")));

        var dto = service.createSession(INTRUDER, new CreateSessionRequest("CAB-001", "K-REPLAY"));

        assertEquals("S-MINE", dto.sessionId());
        verify(distributedLockService, never()).tryLock(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any(ShoppingSession.class));
    }

    /**
     * 🔴 2026-11-10 审计 P0-4（后端半边，铁律 82 ③）：回放必须校验会话状态。
     * 购物已结束的会话（COMPLETED/CANCELLED/DISPUTED）若按 200 回放，客户端会把旧会话
     * 当「开门成功」——柜门没开，人还被带进上一笔账单。幂等键的存活期 = 一次开门意图，
     * 会话终态即意图终止，重试必须重新发起而不是复用旧键。
     */
    @Test
    void createSession_replayingTerminalSession_conflictsInsteadOfFakeSuccess() {
        for (SessionState ended : new SessionState[] {
                SessionState.COMPLETED, SessionState.CANCELLED, SessionState.DISPUTED}) {
            ShoppingSession done = existing("S-DONE", OWNER, "CAB-001");
            done.setState(ended);
            when(repository.findByIdempotencyKey("K-REPLAY")).thenReturn(Optional.of(done));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.createSession(OWNER, new CreateSessionRequest("CAB-001", "K-REPLAY")),
                    () -> ended + " 会话不得按开门成功回放");

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), () -> ended + " 必须拒，不能冒充开门成功");
            verify(distributedLockService, never()).tryLock(anyString(), anyLong(), anyLong());
            verify(repository, never()).save(any(ShoppingSession.class));
        }
    }

    @Test
    void createSession_replayingFailedSession_stillReplaysForRetryWindow() {
        // 对照组（防判据过宽）：FAILED 会话在重试窗口内原样回放——客户端据此展示
        // 上次开门失败原因；把 FAILED 也打死会让「网络重试看到失败详情」变成凭空 409。
        ShoppingSession failed = existing("S-FAILED", INTRUDER, "CAB-001");
        failed.setState(SessionState.FAILED);
        when(repository.findByIdempotencyKey("K-REPLAY")).thenReturn(Optional.of(failed));

        var dto = service.createSession(INTRUDER, new CreateSessionRequest("CAB-001", "K-REPLAY"));

        assertEquals("S-FAILED", dto.sessionId());
        assertEquals(SessionState.FAILED, dto.state());
    }
}
