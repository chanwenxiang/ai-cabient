package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 储值等级：等级判定 = 「累计消费」or「累计净充值」取高。
 *
 * <p>规则表口径来自迁移 V283（消费门槛 0/1000/5000/10000，储值门槛 0/500/2000/5000），
 * 与 {@code member_level_rule.min_recharge} 的量纲一致（元）。
 *
 * <p>净充值的**金额口径**（amount − refunded_cents、status ∈ PAID/REFUNDED）在
 * {@code RechargeOrderMapper.xml} 的 SQL 里，本单测只覆盖 Java 侧判定；
 * 该 SQL 由运行期取证单独验证。
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceDepositLevelTest {

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

    // ── 储值口径 ────────────────────────────────────────────────────────────────

    @Test
    void rechargeOnly_upgradesByDepositTier_whileSpentIsZero() {
        long userId = 9001L;
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "0");
        stubLockAndMember(userId, member);
        stubRules(defaultRules());
        // 消费 0，但累计净充值 5000 元 ⇒ 白金
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(500_000L);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_PLATINUM, member.getMemberLevel());
    }

    @Test
    void rechargeOnly_doesNotUpgradeBelowFirstDepositTier() {
        long userId = 9002L;
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "0");
        stubLockAndMember(userId, member);
        stubRules(defaultRules());
        // 499 元 < SILVER 的 500 门槛 ⇒ 维持 NORMAL
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(49_900L);

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_NORMAL, member.getMemberLevel());
        verify(memberRepository, never()).save(any(Member.class));
    }

    // ── 消费口径（回归：改动不得破坏既有行为）────────────────────────────────────

    @Test
    void spentOnly_stillUpgradesByLegacySpendTier() {
        long userId = 9003L;
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "0");
        stubLockAndMember(userId, member);
        stubRules(defaultRules());
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(0L);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pointsLogRepository.existsByMemberAndSource(any(), any(), any())).thenReturn(false);

        // 消费 1500 元 ⇒ 落在 SILVER 的 [1000, 5000)
        memberService.onOrderPaid(userId, 150_000, "O-1003");

        assertEquals(MemberService.LEVEL_SILVER, member.getMemberLevel());
    }

    @Test
    void takesHigherTierOfBothPaths() {
        long userId = 9004L;
        // 已消费 1200 元 ⇒ 消费口径只到 SILVER
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "1200");
        stubLockAndMember(userId, member);
        stubRules(defaultRules());
        // 净充值 3000 元 ⇒ 储值口径到 GOLD，应取高
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(300_000L);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_GOLD, member.getMemberLevel());
    }

    // ── 边界：无储值路径 / 规则表为空 ───────────────────────────────────────────

    @Test
    void nullMinRecharge_meansTierHasNoDepositPath() {
        long userId = 9005L;
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "0");
        stubLockAndMember(userId, member);
        stubRules(List.of(
                rule("NORMAL", 0, 1000, null),
                rule("SILVER", 1000, 5000, null)));
        // 充值再多也不该升级 —— 这两档没有储值路径
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(999_999_900L);

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_NORMAL, member.getMemberLevel());
        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    void emptyRuleTable_fallsBackToNormalWithoutThrowing() {
        long userId = 9006L;
        Member member = memberOf(userId, MemberService.LEVEL_NORMAL, "0");
        stubLockAndMember(userId, member);
        stubRules(List.of());
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(999_999_900L);

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_NORMAL, member.getMemberLevel());
    }

    // ── 会员不存在 / 幂等 ──────────────────────────────────────────────────────

    @Test
    void refreshLevelOnRecharge_createsMemberWhenAbsentThenUpgrades() {
        long userId = 9007L;
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(userId), 60L, 5L)).thenReturn(true);
        when(memberRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
        when(memberRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> {
            Member m = inv.getArgument(0);
            if (m.getMemberId() == null) {
                m.setMemberId(6007L);
            }
            return m;
        });
        stubRules(defaultRules());
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(500_000L);

        memberService.refreshLevelOnRecharge(userId);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository, atLeast(1)).save(captor.capture());
        Member persisted = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(userId, persisted.getUserId());
        assertEquals(MemberService.LEVEL_PLATINUM, persisted.getMemberLevel());
    }

    @Test
    void refreshLevelOnRecharge_isNoOpWhenLevelAlreadyCorrect() {
        long userId = 9008L;
        Member member = memberOf(userId, MemberService.LEVEL_SILVER, "1200");
        stubLockAndMember(userId, member);
        stubRules(defaultRules());
        when(rechargeOrderRepository.sumNetRechargeByUser(userId)).thenReturn(0L);

        memberService.refreshLevelOnRecharge(userId);

        assertEquals(MemberService.LEVEL_SILVER, member.getMemberLevel());
        verify(memberRepository, never()).save(any(Member.class));
    }

    // ── 反向对照：运营台保存规则不得抹掉新增的储值门槛 ──────────────────────────

    /**
     * {@code min_recharge} 尚未纳入 {@link MemberLevelRuleDto} 契约（admin 列留待第二段）。
     * 本用例锁住「未纳入契约的列不会被既有保存动作清掉」这一前提 ——
     * 一旦有人给 doUpsert 加 {@code rule.setMinRecharge(dto.minRecharge())}，
     * 在 DTO 仍无该字段时会把门槛变成 null，这里必须先红。
     */
    @Test
    void adminUpsert_leavesMinRechargeUntouched() {
        MemberLevelRule existing = rule("GOLD", 5000, 10000, 2000);
        existing.setId(77L);
        when(distributedLockService.tryLock(
                MemberLevelAdminService.levelIdLockKey(77L), 60L, 5L)).thenReturn(true);
        when(levelRuleRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(existing));
        when(levelRuleRepository.findByLevelCode("GOLD")).thenReturn(Optional.of(existing));
        when(levelRuleRepository.save(any(MemberLevelRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MemberLevelAdminService adminService =
                new MemberLevelAdminService(levelRuleRepository, distributedLockService);
        adminService.update(new MemberLevelRuleDto(77L, "GOLD", "金卡会员",
                new BigDecimal("5000"), new BigDecimal("10000"), 0, null,
                BigDecimal.ONE, new BigDecimal("5"), 3, "ACTIVE"));

        ArgumentCaptor<MemberLevelRule> captor = ArgumentCaptor.forClass(MemberLevelRule.class);
        verify(levelRuleRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("2000").compareTo(captor.getValue().getMinRecharge()),
                "运营台保存后 min_recharge 被清空 ⇒ 储值门槛会丢失");
        assertEquals(0, new BigDecimal("5000").compareTo(captor.getValue().getMinSpent()));
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────────

    /** 迁移 V283 落地后的规则表。 */
    private static List<MemberLevelRule> defaultRules() {
        return List.of(
                rule("NORMAL", 0, 1000, 0),
                rule("SILVER", 1000, 5000, 500),
                rule("GOLD", 5000, 10000, 2000),
                rule("PLATINUM", 10000, null, 5000));
    }

    private static MemberLevelRule rule(String code, Integer minSpent, Integer maxSpent,
                                        Integer minRecharge) {
        MemberLevelRule r = new MemberLevelRule();
        r.setLevelCode(code);
        r.setMinSpent(minSpent == null ? null : BigDecimal.valueOf(minSpent));
        r.setMaxSpent(maxSpent == null ? null : BigDecimal.valueOf(maxSpent));
        r.setMinRecharge(minRecharge == null ? null : BigDecimal.valueOf(minRecharge));
        r.setStatus("ACTIVE");
        return r;
    }

    private static Member memberOf(long userId, String level, String totalSpent) {
        Member m = new Member();
        m.setMemberId(userId * 10);
        m.setUserId(userId);
        m.setMemberLevel(level);
        m.setTotalSpent(new BigDecimal(totalSpent));
        m.setOrderCount(0);
        return m;
    }

    private void stubRules(List<MemberLevelRule> rules) {
        when(levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE")).thenReturn(rules);
    }

    private void stubLockAndMember(long userId, Member member) {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(userId), 60L, 5L)).thenReturn(true);
        when(memberRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(member));
    }
}
