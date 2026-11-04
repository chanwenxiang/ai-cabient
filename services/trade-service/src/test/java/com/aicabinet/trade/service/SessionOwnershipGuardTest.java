package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SessionCartRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话族写端点的**归属闸**回归（MA-154 第二轮第一批）。
 *
 * <p>为什么单独成类：IDOR 轴第一轮核过 {@code sessions/{id}/cancel}、
 * {@code sessions/{id}/demo-close}、{@code sessions/{id}/cart} 三条都有归属校验，
 * 但**没有任何用例钉住它**——归属闸是那种「删掉一行也全绿」的代码，
 * 现有测试全是「记录不存在 / 状态不对 / 锁忙」，那些换别人的 id 一样会失败但原因不同，
 * 不能当等价覆盖（这条口径见 lessons 与 MA-154 备注）。
 *
 * <p>每条都是双向：他人重放 ⇒ 期望码 + 没有任何写入；本人 ⇒ 过闸（正向对照防「闸写反」）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionOwnershipGuardTest {

    private static final Long OWNER = 1001L;
    private static final Long INTRUDER = 2002L;

    @Mock private ShoppingSessionMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private SessionService service;
    private SessionLiveCartService cartService;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                repository, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, distributedLockService, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        cartService = new SessionLiveCartService(repository, null, null, null, service);
        when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(60L),
                org.mockito.ArgumentMatchers.eq(5L))).thenReturn(true);
    }

    private ShoppingSession session(String id, Long userId, SessionState state) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(id);
        s.setUserId(userId);
        s.setState(state);
        return s;
    }

    private static org.springframework.http.HttpStatusCode status(ResponseStatusException e) {
        return e.getStatusCode();
    }

    @Test
    void cancelSession_foreignUser_forbiddenAndNothingWritten() {
        when(repository.findByIdForUpdate("S-OWN-1"))
                .thenReturn(Optional.of(session("S-OWN-1", OWNER, SessionState.CREATED)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.cancelSession(INTRUDER, "S-OWN-1"));

        assertEquals(HttpStatus.FORBIDDEN, status(ex), "他人会话必须 403，而不是让它走到取消分支");
        verify(repository, never()).save(any(ShoppingSession.class));
    }

    @Test
    void cancelSession_owner_passesOwnershipGate() {
        // 正向对照：本人过得了归属闸（写反成「只有非本人放行」时这条会红）
        when(repository.findByIdForUpdate("S-OWN-2"))
                .thenReturn(Optional.of(session("S-OWN-2", OWNER, SessionState.CANCELLED)));

        assertDoesNotThrow(() -> service.cancelSession(OWNER, "S-OWN-2"));
    }

    @Test
    void demoCloseSession_foreignUser_notFoundToAvoidExistenceProbe() {
        when(repository.findById("S-OWN-3"))
                .thenReturn(Optional.of(session("S-OWN-3", OWNER, SessionState.SHOPPING)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.demoCloseSession(INTRUDER, "S-OWN-3"));

        assertEquals(HttpStatus.NOT_FOUND, status(ex),
                "他人会话要回 404（不是 403）——403 等于告诉探测者「这个会话号存在」");
        verify(repository, never()).save(any(ShoppingSession.class));
    }

    @Test
    void updateSessionCart_foreignUser_forbiddenBeforeAnyItemWrite() {
        when(repository.findByIdForUpdate("S-OWN-4"))
                .thenReturn(Optional.of(session("S-OWN-4", OWNER, SessionState.SHOPPING)));

        SessionCartRequest req = new SessionCartRequest(List.of());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> cartService.updateSessionCart(INTRUDER, "S-OWN-4", req));

        assertEquals(HttpStatus.FORBIDDEN, status(ex));
        verify(repository, never()).save(any(ShoppingSession.class));
    }

    @Test
    void requireSessionOwner_ownerPasses_intruderThrows() {
        ShoppingSession s = session("S-OWN-5", OWNER, SessionState.SHOPPING);
        assertDoesNotThrow(() -> service.requireSessionOwner(OWNER, s));
        assertEquals(HttpStatus.FORBIDDEN,
                status(assertThrows(ResponseStatusException.class,
                        () -> service.requireSessionOwner(INTRUDER, s))));
    }
}
