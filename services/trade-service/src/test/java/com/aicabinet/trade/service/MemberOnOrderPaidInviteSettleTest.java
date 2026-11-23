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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营销 2：首单结算钩子挂在 {@code onOrderPaid} 上的行为。
 *
 * <p>支付成功的三条路径（支付分回调 / 落单结算 / 欠款补缴）都汇到这里，所以这里既是
 * 「一定发得出」的保证点，也是「重复回调不重发」的唯一闸门。
 */
@ExtendWith(MockitoExtension.class)
class MemberOnOrderPaidInviteSettleTest {

    @Mock private MemberMapper memberRepository;
    @Mock private MemberLevelRuleMapper levelRuleRepository;
    @Mock private MemberPointsLogMapper pointsLogRepository;
    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private FirstOrderInviteSettler inviteSettler;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, levelRuleRepository, pointsLogRepository,
                rechargeOrderRepository, distributedLockService, inviteSettler, null);
        ReflectionTestUtils.setField(memberService, "self", memberService);
        when(distributedLockService.tryLock(MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER", "O-1")).thenReturn(false);
        when(levelRuleRepository.findByLevelCode(MemberService.LEVEL_NORMAL)).thenReturn(Optional.empty());
    }

    @Test
    void onOrderPaid_whenInvited_settlesAndMarksSettled() {
        Member member = member(20002L, null);
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(inviteSettler.settleInviterReward(10001L, 20002L, "O-1")).thenReturn(999L);

        memberService.onOrderPaid(10001L, 1999, "O-1");

        assertNotNull(member.getInviteSettledAt(), "结算成功后必须落结算时间，否则重复回调会二次发券");
        assertEquals(999L, member.getInviterRewardCouponId());
        assertEquals(1, member.getOrderCount());
    }

    @Test
    void onOrderPaid_whenNotInvited_doesNotTouchInviteSettler() {
        Member member = member(null, null);
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));

        memberService.onOrderPaid(10001L, 1999, "O-1");

        verify(inviteSettler, never()).settleInviterReward(anyLong(), anyLong(), anyString());
        assertNull(member.getInviteSettledAt());
    }

    @Test
    void onOrderPaid_whenAlreadySettled_doesNotSettleAgain() {
        Member member = member(20002L, Instant.now());
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));

        memberService.onOrderPaid(10001L, 1999, "O-1");

        verify(inviteSettler, never()).settleInviterReward(anyLong(), anyLong(), anyString());
    }

    /**
     * 发券失败不能连累会员统计/积分：异常必须在本方法内被兜住（否则 onOrderPaid 的
     * 事务会被标 rollback-only，变成「钱收了、积分和消费额都没了」）。
     */
    @Test
    void onOrderPaid_whenSettleThrows_statsStillCountedAndMarkStaysNull() {
        Member member = member(20002L, null);
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(inviteSettler.settleInviterReward(anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("coupon service down"));
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.onOrderPaid(10001L, 1999, "O-1");

        assertEquals(1, member.getOrderCount(), "消费统计不能因发券失败回滚");
        assertEquals(0, new java.math.BigDecimal("19.99").compareTo(member.getTotalSpent()));
        assertNull(member.getInviteSettledAt(), "未结算要留空，下一单重试");
        verify(memberRepository, org.mockito.Mockito.atLeastOnce()).save(any(Member.class));
    }

    private static Member member(Long invitedBy, Instant settledAt) {
        Member m = new Member();
        m.setMemberId(1L);
        m.setUserId(10001L);
        m.setInviteCode("MYCODE23");
        m.setInvitedBy(invitedBy);
        m.setInviteSettledAt(settledAt);
        m.setOrderCount(0);
        m.setTotalSpent(java.math.BigDecimal.ZERO);
        m.setAvailablePoints(0);
        return m;
    }
}
