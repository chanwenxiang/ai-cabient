package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberInviteSummaryDto;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.mapper.MemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营销 2：邀请绑定/结算/补码。
 *
 * <p>重点不是「happy path 能过」，而是四条拒绝口径与「绑定失败不能白送券」：
 * 邀请券是真金白银，任何一条把券发出去但没绑成的路径都是可刷的洞。
 */
@ExtendWith(MockitoExtension.class)
class MemberInviteServiceTest {

    @Mock private MemberMapper memberRepository;
    @Mock private InviteCouponIssuer couponIssuer;
    @Mock private SystemConfigService systemConfigService;

    private MemberInviteService service;

    @BeforeEach
    void setUp() {
        service = new MemberInviteService(memberRepository, couponIssuer, systemConfigService);
    }

    // ── 绑定：四条拒绝 ─────────────────────────────────────────────────────────

    @Test
    void bind_whenInviteCodeNotFound_returns404() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(member(null, 0)));
        when(memberRepository.findByInviteCode("NOPE2345")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "NOPE2345"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    @Test
    void bind_whenBindSelf_returns400() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(member(null, 0)));
        when(memberRepository.findByInviteCode("SELF2345")).thenReturn(Optional.of(member(null, 0)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "SELF2345"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    @Test
    void bind_whenAlreadyBound_returns409() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(member(42L, 0)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "ANY23456"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    /** 老用户不能被「补录」成新客：否则等于给存量用户倒送拉新成本。 */
    @Test
    void bind_whenAlreadyHasOrders_returns409() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(member(null, 1)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "ANY23456"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    @Test
    void bind_whenFeatureDisabled_returns400() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "ANY23456"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(memberRepository, never()).bindInviter(anyLong(), anyLong(), any());
    }

    // ── 绑定：成功与失败边界 ───────────────────────────────────────────────────

    @Test
    void bind_success_writesBindingAndIssuesNewcomerCoupon() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        Member me = member(null, 0);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(me));
        when(memberRepository.findByInviteCode("INV23456")).thenReturn(Optional.of(inviter(20002L, "INV23456")));
        when(memberRepository.bindInviter(eq(10001L), eq(20002L), any(Instant.class))).thenReturn(1);
        when(couponIssuer.issue(10001L, InviteCouponIssuer.CODE_NEWCOMER)).thenReturn(555L);
        when(memberRepository.countByInvitedBy(anyLong())).thenReturn(0L);
        when(memberRepository.countSettledByInvitedBy(anyLong())).thenReturn(0L);

        MemberInviteSummaryDto dto = service.bind(10001L, " inv23456 ");

        assertEquals("MYCODE23", dto.inviteCode());
        verify(memberRepository).bindInviter(eq(10001L), eq(20002L), any(Instant.class));
        verify(memberRepository).writeInviteeCouponId(10001L, 555L);
        verify(couponIssuer).issue(10001L, InviteCouponIssuer.CODE_NEWCOMER);
    }

    /**
     * 发券失败（券发完/预算不足）不能把绑定一起否掉：绑定已落库，券等下次打开邀请页补发。
     * 也不允许异常冒到前端——用户不该因为「券发完了」看到绑定失败。
     */
    @Test
    void bind_whenCouponIssueFails_stillKeepsBinding() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        Member me = member(null, 0);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(me));
        when(memberRepository.findByInviteCode("INV23456")).thenReturn(Optional.of(inviter(20002L, "INV23456")));
        when(memberRepository.bindInviter(eq(10001L), eq(20002L), any(Instant.class))).thenReturn(1);
        when(couponIssuer.issue(10001L, InviteCouponIssuer.CODE_NEWCOMER))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已发完"));
        when(memberRepository.countByInvitedBy(anyLong())).thenReturn(0L);
        when(memberRepository.countSettledByInvitedBy(anyLong())).thenReturn(0L);

        service.bind(10001L, "INV23456");

        verify(memberRepository).bindInviter(eq(10001L), eq(20002L), any(Instant.class));
        verify(memberRepository, never()).writeInviteeCouponId(anyLong(), anyLong());
    }

    /** 条件更新落空（并发下已被绑/per 已下单）必须报 409，且不发券。 */
    @Test
    void bind_whenConditionalUpdateMisses_returns409WithoutCoupon() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(member(null, 0)));
        when(memberRepository.findByInviteCode("INV23456")).thenReturn(Optional.of(inviter(20002L, "INV23456")));
        when(memberRepository.bindInviter(eq(10001L), eq(20002L), any(Instant.class))).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bind(10001L, "INV23456"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    // ── 首单结算 ──────────────────────────────────────────────────────────────

    @Test
    void settle_whenDisabled_returnsNull() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(false);

        assertNull(service.settleInviterReward(10001L, 20002L, "O-1"));
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    @Test
    void settle_whenInviterIsSelf_returnsNull() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);

        assertNull(service.settleInviterReward(10001L, 10001L, "O-1"));
        verify(couponIssuer, never()).issue(anyLong(), any());
    }

    @Test
    void settle_issuesInviterCoupon() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(couponIssuer.issue(20002L, InviteCouponIssuer.CODE_INVITER)).thenReturn(777L);

        assertEquals(777L, service.settleInviterReward(10001L, 20002L, "O-1"));
    }

    /** 券模板下架时 issue 返回 null ⇒ 结算返回 null（调用方保持「未结算」，等下次重试）。 */
    @Test
    void settle_whenCouponTemplateInactive_returnsNull() {
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(couponIssuer.issue(20002L, InviteCouponIssuer.CODE_INVITER)).thenReturn(null);

        assertNull(service.settleInviterReward(10001L, 20002L, "O-1"));
    }

    // ── 我的邀请码 ────────────────────────────────────────────────────────────

    @Test
    void summary_whenCodeMissing_backfillsAndReturnsIt() {
        Member me = member(null, 0);
        me.setInviteCode(null);
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(me));
        when(memberRepository.existsInviteCode(any())).thenReturn(false);
        when(memberRepository.writeInviteCodeIfAbsent(eq(10001L), any())).thenReturn(1);
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.countByInvitedBy(anyLong())).thenReturn(3L);
        when(memberRepository.countSettledByInvitedBy(anyLong())).thenReturn(1L);

        MemberInviteSummaryDto dto = service.summary(10001L);

        assertNotNull(dto.inviteCode());
        assertEquals(8, dto.inviteCode().length());
        assertEquals(3, dto.inviteeCount());
        assertEquals(1, dto.rewardedCount());
    }

    /** 已有码不覆盖：并发补码时只有赢家写进去，另一个读回同一个码。 */
    @Test
    void summary_whenCodeExists_doesNotRewrite() {
        Member me = member(null, 0);
        me.setInviteCode("EXIST234");
        when(memberRepository.findByUserId(10001L)).thenReturn(Optional.of(me));
        when(systemConfigService.getBoolean(MemberInviteService.KEY_ENABLED, false)).thenReturn(true);
        when(memberRepository.countByInvitedBy(anyLong())).thenReturn(0L);
        when(memberRepository.countSettledByInvitedBy(anyLong())).thenReturn(0L);

        assertEquals("EXIST234", service.summary(10001L).inviteCode());
        verify(memberRepository, never()).writeInviteCodeIfAbsent(anyLong(), any());
    }

    private static Member member(Long invitedBy, int orderCount) {
        return member(10001L, "MYCODE23", invitedBy, orderCount);
    }

    private static Member inviter(Long userId, String inviteCode) {
        return member(userId, inviteCode, null, 3);
    }

    private static Member member(Long userId, String inviteCode, Long invitedBy, int orderCount) {
        Member m = new Member();
        m.setMemberId(1L);
        m.setUserId(userId);
        m.setInviteCode(inviteCode);
        m.setInvitedBy(invitedBy);
        m.setOrderCount(orderCount);
        return m;
    }
}
