package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberOnOrderPaidConcurrencyTest {

    @Mock private MemberMapper memberRepository;
    @Mock private MemberLevelRuleMapper levelRuleRepository;
    @Mock private MemberPointsLogMapper pointsLogRepository;
    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private DistributedLockService distributedLockService;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, levelRuleRepository, pointsLogRepository,
                rechargeOrderRepository, distributedLockService, null);
        ReflectionTestUtils.setField(memberService, "self", memberService);
    }

    @Test
    void onOrderPaid_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> memberService.onOrderPaid(10001L, 500, "O-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    /**
     * P2-3/P3-3：同一订单走两条补缴路径时，累计消费与订单数不得双计。
     * 旧实现只有积分腿幂等（existsByMemberAndSource），消费额腿无条件累加。
     */
    @Test
    void onOrderPaid_whenOrderAlreadyCounted_skipsStatsAndPoints() {
        when(distributedLockService.tryLock(MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        Member member = member();
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER", "O-1")).thenReturn(true);

        memberService.onOrderPaid(10001L, 1999, "O-1");

        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(member.getTotalSpent()),
                "同单第二次支付回调不得再累加消费额");
        assertEquals(0, member.getOrderCount());
        verify(memberRepository, never()).save(any(Member.class));
        verify(pointsLogRepository, never()).save(any(com.aicabinet.trade.domain.MemberPointsLog.class));
    }

    /** 首次支付照旧累计：去重不能把正常单也挡掉。 */
    @Test
    void onOrderPaid_firstTime_accumulatesStats() {
        when(distributedLockService.tryLock(MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        Member member = member();
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER", "O-1")).thenReturn(false);
        when(levelRuleRepository.findByLevelCode(MemberService.LEVEL_NORMAL)).thenReturn(Optional.empty());
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.onOrderPaid(10001L, 1999, "O-1");

        assertEquals(0, new java.math.BigDecimal("19.99").compareTo(member.getTotalSpent()));
        assertEquals(1, member.getOrderCount());
    }

    /**
     * R20-10/13 的地基：金额不足 1 分/倍率时返 0 分也必须落一行「该单已计入」占位，
     * 否则 onOrderPaid 的去重判据在 0 分单上失效（同一单会被累加两次消费额）。
     */
    @Test
    void earnPoints_zeroPoints_stillWritesMarkerRowWithoutExpiry() {
        Member member = member();
        when(levelRuleRepository.findByLevelCode(MemberService.LEVEL_NORMAL)).thenReturn(Optional.empty());

        memberService.earnPoints(member, 50, "O-2");

        org.mockito.ArgumentCaptor<com.aicabinet.trade.domain.MemberPointsLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.aicabinet.trade.domain.MemberPointsLog.class);
        verify(pointsLogRepository).save(captor.capture());
        com.aicabinet.trade.domain.MemberPointsLog saved = captor.getValue();
        assertEquals(0, saved.getPoints());
        assertEquals("ORDER", saved.getSourceType());
        assertEquals("O-2", saved.getSourceId());
        assertNull(saved.getExpireAt(), "0 分行不设有效期，避免被积分过期扫描拾取");
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(saved.getEarnRate()), "倍率照样落库");
        assertEquals(0, member.getAvailablePoints(), "0 分不动余额");
    }

    private static Member member() {
        Member member = new Member();
        member.setMemberId(1L);
        member.setUserId(10001L);
        member.setMemberLevel(MemberService.LEVEL_NORMAL);
        member.setTotalSpent(java.math.BigDecimal.ZERO);
        member.setOrderCount(0);
        member.setTotalPoints(0);
        member.setAvailablePoints(0);
        member.setUsedPoints(0);
        member.setExpiredPoints(0);
        return member;
    }

    @Test
    void onOrderPaid_unlocksLockAfterSuccess() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        Member member = new Member();
        member.setMemberId(1L);
        member.setUserId(10001L);
        member.setMemberLevel(MemberService.LEVEL_NORMAL);
        member.setTotalSpent(java.math.BigDecimal.ZERO);
        member.setOrderCount(0);
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(levelRuleRepository.findByLevelCode(MemberService.LEVEL_NORMAL)).thenReturn(Optional.empty());
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER", "O-1")).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.onOrderPaid(10001L, 500, "O-1");

        verify(distributedLockService).unlock(MemberService.memberUserLockKey(10001L));
    }
}
