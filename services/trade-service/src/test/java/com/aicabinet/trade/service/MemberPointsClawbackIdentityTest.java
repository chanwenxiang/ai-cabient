package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款扣回必须同时归入消耗桶，保持 total = available + used + expired 恒等（CB-058）。 */
@ExtendWith(MockitoExtension.class)
class MemberPointsClawbackIdentityTest {

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
    }

    @Test
    void clawback_movesDeductedPointsIntoUsedBucket() {
        Member member = member(100, 50, 30, 20);
        stubLockAndLoad(member);
        when(levelRuleRepository.findByLevelCode(member.getMemberLevel()))
                .thenReturn(Optional.of(rule(BigDecimal.ONE)));

        // 退 5.00 元 ⇒ 按比例扣回 5 分
        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(100, member.getTotalPoints(), "累计发放只增不减");
        assertEquals(45, member.getAvailablePoints());
        assertEquals(35, member.getUsedPoints(), "扣回属消耗，必须归入已用桶");
        assertEquals(20, member.getExpiredPoints());
        assertIdentity(member);

        MemberPointsLog log = captureLog();
        assertEquals("USE", log.getPointsType());
        assertEquals(-5, log.getPoints());
    }

    @Test
    void clawback_keepsIdentityWhenAvailableIsInsufficient() {
        Member member = member(100, 3, 77, 20);
        stubLockAndLoad(member);
        when(levelRuleRepository.findByLevelCode(member.getMemberLevel()))
                .thenReturn(Optional.of(rule(BigDecimal.ONE)));

        // 应扣 5 分但只剩 3 分可用 ⇒ 扣到 0 为止（不产生负积分）
        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(100, member.getTotalPoints());
        assertEquals(0, member.getAvailablePoints());
        assertEquals(80, member.getUsedPoints());
        assertIdentity(member);
        assertEquals(-3, captureLog().getPoints());
    }

    @Test
    void clawback_isIdempotentOnSameRefundKey() {
        Member member = member(100, 50, 30, 20);
        stubLockAndLoad(member);
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER_REFUND", "O-1:R-1")).thenReturn(true);

        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(50, member.getAvailablePoints());
        assertEquals(30, member.getUsedPoints());
        verify(pointsLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void stubLockAndLoad(Member member) {
        when(distributedLockService.tryLock(MemberService.memberUserLockKey(200L), 60L, 5L)).thenReturn(true);
        when(memberRepository.findByUserIdForUpdate(200L)).thenReturn(Optional.of(member));
    }

    private MemberPointsLog captureLog() {
        ArgumentCaptor<MemberPointsLog> captor = ArgumentCaptor.forClass(MemberPointsLog.class);
        verify(pointsLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private static void assertIdentity(Member member) {
        assertEquals(member.getTotalPoints(),
                member.getAvailablePoints() + member.getUsedPoints() + member.getExpiredPoints(),
                "巡检判据 POINTS_IDENTITY 的恒等式");
    }

    private static Member member(int total, int available, int used, int expired) {
        Member member = new Member();
        member.setMemberId(1L);
        member.setUserId(200L);
        member.setMemberLevel(MemberService.LEVEL_NORMAL);
        member.setTotalPoints(total);
        member.setAvailablePoints(available);
        member.setUsedPoints(used);
        member.setExpiredPoints(expired);
        return member;
    }

    private static MemberLevelRule rule(BigDecimal pointsRate) {
        MemberLevelRule rule = new MemberLevelRule();
        rule.setPointsRate(pointsRate);
        return rule;
    }
}
