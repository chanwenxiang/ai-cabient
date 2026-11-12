package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R20-5：积分过期「部分结转」判据钉测。
 *
 * <p>真事故形态：EARN=100、当前可用余额 10 ⇒ 本轮只过期 10。旧实现因为
 * {@code pts > remaining} 整条跳过且不写 {@code expired_at}，下一轮同一行按 100 重算，
 * 用户新赚 100 后再扣 100 ⇒ 同一笔 EARN 累计过期 110 &gt; 100（直接多扣用户资产）。</p>
 *
 * <p>判据（报告 R20-5）：**同一 EARN 行累计过期 ≤ 其 points**。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PointsExpiryPartialTest {

    @Mock MemberPointsLogMapper pointsLogRepository;
    @Mock MemberMapper memberRepository;
    @Mock NotificationService notificationService;
    @Mock ScheduledTaskService taskService;

    private PointsExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PointsExpiryScheduler(
                pointsLogRepository, memberRepository, notificationService, null, taskService);
    }

    private static Member member(int available) {
        Member m = new Member();
        m.setMemberId(1L);
        m.setUserId(10001L);
        m.setAvailablePoints(available);
        m.setExpiredPoints(0);
        return m;
    }

    private static MemberPointsLog earn(int points, Instant expireAt) {
        MemberPointsLog l = new MemberPointsLog();
        l.setMemberId(1L);
        l.setPoints(points);
        l.setPointsType("EARN");
        l.setExpireAt(expireAt);
        return l;
    }

    @Test
    void partialExpiry_marksOnlyBudget_andSameEarnNeverExpiresTwice() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        MemberPointsLog earn = earn(100, past);
        Member member = member(10);
        when(pointsLogRepository.findEarnExpiredBefore(any())).thenReturn(List.of(earn));
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));

        scheduler.expire();

        assertEquals(0, member.getAvailablePoints(), "余额只有 10 ⇒ 本轮只能扣 10");
        assertEquals(10, earn.getExpiredPoints(), "部分结转必须记住这 10 分已过期");
        assertNull(earn.getExpiredAt(), "整行未耗尽不能置 expired_at——置了就再也不会被重算，剩余 90 永久不过期");

        // 用户新赚 100 ⇒ 余额回到 100；同一 EARN 行仍入选（expired_at 为空）
        member.setAvailablePoints(100);
        scheduler.expire();

        assertEquals(100, earn.getExpiredPoints(), "累计过期封顶在面额 100（第二轮补扣 90）");
        assertEquals(10, member.getAvailablePoints(), "100（新赚）− 90（补扣）= 10");
        assertNotNull(earn.getExpiredAt(), "整行耗尽后置 expired_at");
    }

    @Test
    void budget_ignoresAlreadyExpiredPart() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        MemberPointsLog earn = earn(100, past);
        earn.setExpiredPoints(60);
        Member member = member(500);
        when(pointsLogRepository.findEarnExpiredBefore(any())).thenReturn(List.of(earn));
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));

        scheduler.expire();

        // 预算 = min(余额, 本行剩余 40)；若按原始 100 求和会把已过期的 60 再扣一次
        assertEquals(100, earn.getExpiredPoints(), "只补扣剩余 40，累计到面额 100");
        assertEquals(460, member.getAvailablePoints());
    }
}
