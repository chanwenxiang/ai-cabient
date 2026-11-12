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

    /** R20-10：扣回用**发放时**倍率，不用当前等级倍率（下单后升级再退款不得多扣）。 */
    @Test
    void clawback_usesEarnRateInsteadOfCurrentLevelRate() {
        Member member = member(100, 100, 0, 0);
        member.setTotalSpent(new BigDecimal("100.00"));
        stubLockAndLoad(member);
        // 发放时 1.0 倍：100 元 ⇒ 100 分
        MemberPointsLog earn = new MemberPointsLog();
        earn.setPoints(100);
        earn.setEarnRate(new BigDecimal("1.00"));
        when(pointsLogRepository.findOrderEarn(1L, "O-1")).thenReturn(Optional.of(earn));
        // 已是 2.0 倍等级但本单按下单时的 1.0 倍发放 ⇒ 不得读当前倍率（读不到就是对的：
        // 旧实现会按 2.0 扣 10 分，多扣的 5 分是用户从没拿到的）

        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(-5, captureLog().getPoints(), "退 5 元按发放时 1.0 倍扣 5 分（不是当前 2.0 倍的 10 分）");
        assertEquals(95, member.getAvailablePoints());
    }

    /** 历史 EARN 行没有倍率列（R20-10 之前发放）⇒ 回落当前等级倍率，不比修前更差。 */
    @Test
    void clawback_fallsBackToCurrentRateWhenLegacyEarnRowHasNoRate() {
        Member member = member(100, 100, 0, 0);
        stubLockAndLoad(member);
        MemberPointsLog legacy = new MemberPointsLog();
        legacy.setPoints(100);
        legacy.setEarnRate(null);
        when(pointsLogRepository.findOrderEarn(1L, "O-1")).thenReturn(Optional.of(legacy));
        when(levelRuleRepository.findByLevelCode(member.getMemberLevel()))
                .thenReturn(Optional.of(rule(new BigDecimal("2.00"))));

        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(-10, captureLog().getPoints());
    }

    /** R20-11：全额退款要回收累计消费（净额口径），否则买满升级再全退 = 白拿等级。 */
    @Test
    void refund_rollsBackTotalSpent() {
        Member member = member(100, 100, 0, 0);
        member.setTotalSpent(new BigDecimal("100.00"));
        stubLockAndLoad(member);
        when(pointsLogRepository.findOrderEarn(1L, "O-1")).thenReturn(Optional.empty());

        memberService.clawbackPointsOnRefund(200L, 10000, "O-1", "R-1");

        assertEquals(0, new BigDecimal("0.00").compareTo(member.getTotalSpent()),
                "全额退款后累计消费归零，余额不再是虚高的 100 元");
    }

    /** 跨期/历史退款可能大于当前记账值：夹到 0，不写负数（负消费额会污染等级判定与后台展示）。 */
    @Test
    void refund_neverDrivesTotalSpentNegative() {
        Member member = member(10, 10, 0, 0);
        member.setTotalSpent(new BigDecimal("1.00"));
        stubLockAndLoad(member);
        when(pointsLogRepository.findOrderEarn(1L, "O-1")).thenReturn(Optional.empty());

        memberService.clawbackPointsOnRefund(200L, 500, "O-1", "R-1");

        assertEquals(0, BigDecimal.ZERO.compareTo(member.getTotalSpent()));
    }

    /** 等级只升不降（2026-11-12 拍板）：消费额回落但**不重算等级**。 */
    @Test
    void refund_doesNotRecomputeLevel() {
        Member member = member(100, 100, 0, 0);
        member.setTotalSpent(new BigDecimal("100.00"));
        member.setMemberLevel(MemberService.LEVEL_GOLD);
        stubLockAndLoad(member);
        when(pointsLogRepository.findOrderEarn(1L, "O-1")).thenReturn(Optional.of(earnLogWithRate("1.00")));

        memberService.clawbackPointsOnRefund(200L, 10000, "O-1", "R-1");

        assertEquals(MemberService.LEVEL_GOLD, member.getMemberLevel(), "退到 0 元也不降级");
        verify(levelRuleRepository, never()).findByStatusOrderBySortorderAsc(org.mockito.ArgumentMatchers.any());
    }

    private static MemberPointsLog earnLogWithRate(String rate) {
        MemberPointsLog log = new MemberPointsLog();
        log.setPoints(100);
        log.setEarnRate(new BigDecimal(rate));
        return log;
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
