package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.common.dto.MemberLevelRuleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class MemberService {
    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_SILVER = "SILVER";
    public static final String LEVEL_GOLD = "GOLD";
    public static final String LEVEL_PLATINUM = "PLATINUM";

    private final MemberMapper memberRepository;
    private final MemberLevelRuleMapper levelRuleRepository;
    private final MemberPointsLogMapper pointsLogRepository;
    private final RechargeOrderMapper rechargeOrderRepository;
    private final DistributedLockService distributedLockService;
    private final MemberService self;

    public MemberService(MemberMapper memberRepository,
                         MemberLevelRuleMapper levelRuleRepository,
                         MemberPointsLogMapper pointsLogRepository,
                         RechargeOrderMapper rechargeOrderRepository,
                         DistributedLockService distributedLockService,
                         @Lazy MemberService self) {
        this.memberRepository = memberRepository;
        this.levelRuleRepository = levelRuleRepository;
        this.pointsLogRepository = pointsLogRepository;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public Member createMember(Long userId) {
        return runWithMemberUserLock(userId, () -> {
            Member existing = memberRepository.findByUserIdForUpdate(userId).orElse(null);
            if (existing != null) {
                return existing;
            }

            Member member = new Member();
            member.setUserId(userId);
            member.setMemberLevel(LEVEL_NORMAL);
            member.setTotalSpent(BigDecimal.ZERO);
            member.setOrderCount(0);
            member.setCreatedAt(Instant.now());

            return memberRepository.save(member);
        });
    }

    @Transactional
    public void updateMemberStats(Long memberId, BigDecimal orderAmount) {
        Member preview = memberRepository.findById(memberId).orElse(null);
        if (preview == null || preview.getUserId() == null) {
            return;
        }
        runWithMemberUserLock(preview.getUserId(), () -> {
            doUpdateMemberStats(memberId, orderAmount);
            return null;
        });
    }

    private void doUpdateMemberStats(Long memberId, BigDecimal orderAmount) {
        Member member = memberRepository.findByIdForUpdate(memberId).orElse(null);
        if (member == null) {
            return;
        }
        applyMemberStatsDelta(member, orderAmount);
        memberRepository.save(member);
    }

    private void applyMemberStatsDelta(Member member, BigDecimal orderAmount) {
        member.setTotalSpent(member.getTotalSpent().add(orderAmount));
        int nextCount = Math.addExact(Math.max(0, member.getOrderCount()), 1);
        member.setOrderCount(nextCount);

        applyLevelIfChanged(member);
        member.setUpdatedAt(Instant.now());
    }

    /**
     * 按「累计消费 or 累计净充值」重算等级并写回实体字段（不落库，由调用方 save）。
     *
     * @return 等级是否发生变化
     */
    private boolean applyLevelIfChanged(Member member) {
        String newLevel = calculateMemberLevel(member.getTotalSpent(), netRechargeOf(member.getUserId()));
        String current = member.getMemberLevel();
        if (newLevel.equals(current)) {
            return false;
        }
        member.setMemberLevel(newLevel);
        member.setLevelUpgradeAt(Instant.now());
        log.info("Member level upgraded: memberId={}, from={}, to={}",
                member.getMemberId(), current, newLevel);
        return true;
    }

    /**
     * 累计净充值（元）= 曾支付成功的充值额 − 已原路退回额。
     *
     * <p>刻意**实时聚合** {@code recharge_order} 而不是在 member 上物化一列：
     * 物化列需要在每个入账/退款点挂钩子，漏一处就长期漂移；聚合天然幂等、可自愈，
     * 退款语义也直接由 {@code refunded_cents} 表达。代价是每次等级重算多一次 SUM 查询，
     * 而等级重算只发生在「订单支付后」与「充值入账后」两个低频点。
     */
    private BigDecimal netRechargeOf(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(rechargeOrderRepository.sumNetRechargeByUser(userId), 2);
    }

    /**
     * 等级判定：**累计消费**与**累计净充值**两条口径取高（D1 储值等级）。
     *
     * <p>规则表按 sortorder 升序，逐条判断「消费落在 [minSpent, maxSpent)」或
     * 「净充值 ≥ minRecharge」，取**最后**一个达标档，即最高档。原实现「取首个达标档」
     * 在区间互斥时与取最后等价，故消费口径行为不变；不这样改则储值路径永远走不到
     * （NORMAL 的 minSpent=0 会先把任何输入吃掉）。
     *
     * <p>{@code minRecharge} 为 null 表示该档无储值路径，跳过该口径。
     * 两条口径都单调不减 ⇒ 等级只升不降。规则表为空时回落 NORMAL。
     */
    private String calculateMemberLevel(BigDecimal totalSpent, BigDecimal netRecharge) {
        List<MemberLevelRule> rules = levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE");

        String matched = null;
        for (MemberLevelRule rule : rules) {
            if (matchesSpentTier(rule, totalSpent) || matchesRechargeTier(rule, netRecharge)) {
                matched = rule.getLevelCode();
            }
        }

        return matched != null ? matched : LEVEL_NORMAL;
    }

    private static boolean matchesSpentTier(MemberLevelRule rule, BigDecimal totalSpent) {
        return totalSpent != null
                && rule.getMinSpent() != null
                && totalSpent.compareTo(rule.getMinSpent()) >= 0
                && (rule.getMaxSpent() == null || totalSpent.compareTo(rule.getMaxSpent()) < 0);
    }

    private static boolean matchesRechargeTier(MemberLevelRule rule, BigDecimal netRecharge) {
        return netRecharge != null
                && rule.getMinRecharge() != null
                && netRecharge.compareTo(rule.getMinRecharge()) >= 0;
    }

    public Optional<Member> getMemberByUserId(Long userId) {
        return memberRepository.findByUserId(userId);
    }

    public Optional<Member> getMember(Long memberId) {
        return memberRepository.findById(memberId);
    }

    /** 订单支付成功后累计消费并刷新会员等级。 */
    @Transactional
    public void onOrderPaid(Long userId, int paidAmountCents, String orderId) {
        if (userId == null || paidAmountCents <= 0) {
            return;
        }
        runWithMemberUserLock(userId, () -> {
            Member member = memberRepository.findByUserIdForUpdate(userId)
                    .orElseGet(() -> createMemberIfAbsent(userId));
            applyMemberStatsDelta(member, BigDecimal.valueOf(paidAmountCents, 2));
            memberRepository.save(member);
            self.earnPoints(member, paidAmountCents, orderId);
            return null;
        });
    }

    /**
     * 充值入账后刷新等级（D1「储值即升级」）。
     *
     * <p>⚠️ 用 {@link Propagation#REQUIRES_NEW}：调用方
     * {@code PaymentService.doCreditRecharge} 正处于「充值入账」事务中，本方法失败
     * <b>不得</b>把「钱已入账」一起回滚 —— 挂起外层事务另开一个，内部异常不会把外层
     * 标记为 rollback-only。调用方仍须 try/catch，否则异常会继续向外传播。
     *
     * <p>幂等：只做「读聚合 + 重算 + 需要时写等级」，不做累加，重复调用结果相同，
     * 故不需要幂等键（与 {@code applyRechargeBonus} 的账本流水不同）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshLevelOnRecharge(Long userId) {
        if (userId == null) {
            return;
        }
        runWithMemberUserLock(userId, () -> {
            Member member = memberRepository.findByUserIdForUpdate(userId)
                    .orElseGet(() -> createMemberIfAbsent(userId));
            if (applyLevelIfChanged(member)) {
                member.setUpdatedAt(Instant.now());
                memberRepository.save(member);
            }
            return null;
        });
    }

    private Member createMemberIfAbsent(Long userId) {
        Member existing = memberRepository.findByUserId(userId).orElse(null);
        if (existing != null) {
            return memberRepository.findByUserIdForUpdate(userId).orElse(existing);
        }
        Member member = new Member();
        member.setUserId(userId);
        member.setMemberLevel(LEVEL_NORMAL);
        member.setTotalSpent(BigDecimal.ZERO);
        member.setOrderCount(0);
        member.setCreatedAt(Instant.now());
        return memberRepository.save(member);
    }

    /** 按当前等级积分倍率返积分（1 元 = points_rate 积分），积分有效期 365 天。 */
    @Transactional
    public void earnPoints(Member member, int paidAmountCents, String sourceId) {
        if (member == null || paidAmountCents <= 0) {
            return;
        }
        // 幂等保障：同一订单只返一次积分（配合 V166 唯一索引）
        if (pointsLogRepository.existsByMemberAndSource(member.getMemberId(), "ORDER", sourceId)) {
            log.info("points already earned, skip memberId={} source={}", member.getMemberId(), sourceId);
            return;
        }
        MemberLevelRule rule = levelRuleRepository.findByLevelCode(member.getMemberLevel())
                .orElse(null);
        BigDecimal rate = rule != null && rule.getPointsRate() != null
                ? rule.getPointsRate()
                : BigDecimal.ONE;
        int points = rate.multiply(BigDecimal.valueOf(paidAmountCents, 2))
                .setScale(0, RoundingMode.DOWN).intValue();
        if (points <= 0) {
            return;
        }
        member.setTotalPoints(nz(member.getTotalPoints()) + points);
        member.setAvailablePoints(nz(member.getAvailablePoints()) + points);
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);

        MemberPointsLog pointsLog = new MemberPointsLog();
        pointsLog.setMemberId(member.getMemberId());
        pointsLog.setPoints(points);
        pointsLog.setPointsType("EARN");
        pointsLog.setSourceType("ORDER");
        pointsLog.setSourceId(sourceId);
        pointsLog.setDescription("购物返积分");
        pointsLog.setExpireAt(Instant.now().plus(365, ChronoUnit.DAYS));
        pointsLogRepository.save(pointsLog);
    }

    /**
     * 退款时按比例扣回积分（幂等：同一退款键只扣一次）。
     * 多节点：member 行锁 + 流水 source 唯一索引。
     */
    @Transactional
    public void clawbackPointsOnRefund(Long userId, int refundAmountCents, String orderId, String refundKey) {
        if (userId == null || refundAmountCents <= 0 || orderId == null || orderId.isBlank()) {
            return;
        }
        runWithMemberUserLock(userId, () -> {
            doClawbackPointsOnRefund(userId, refundAmountCents, orderId, refundKey);
            return null;
        });
    }

    private void doClawbackPointsOnRefund(Long userId, int refundAmountCents, String orderId, String refundKey) {
        String sourceId = orderId + ":" + (refundKey == null ? "default" : refundKey);
        Member member = memberRepository.findByUserIdForUpdate(userId).orElse(null);
        if (member == null) {
            return;
        }
        if (pointsLogRepository.existsByMemberAndSource(member.getMemberId(), "ORDER_REFUND", sourceId)) {
            return;
        }
        MemberLevelRule rule = levelRuleRepository.findByLevelCode(member.getMemberLevel()).orElse(null);
        BigDecimal rate = rule != null && rule.getPointsRate() != null
                ? rule.getPointsRate()
                : BigDecimal.ONE;
        int clawback = rate.multiply(BigDecimal.valueOf(refundAmountCents, 2))
                .setScale(0, RoundingMode.DOWN).intValue();
        if (clawback <= 0) {
            return;
        }
        int available = nz(member.getAvailablePoints());
        int actual = Math.min(clawback, available);
        if (actual <= 0) {
            return;
        }
        member.setAvailablePoints(available - actual);
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);

        MemberPointsLog entry = new MemberPointsLog();
        entry.setMemberId(member.getMemberId());
        entry.setPoints(-actual);
        entry.setPointsType("USE");
        entry.setSourceType("ORDER_REFUND");
        entry.setSourceId(sourceId);
        entry.setDescription("退款扣回积分");
        pointsLogRepository.save(entry);
        log.info("points clawback memberId={} order={} refund={} points={}",
                member.getMemberId(), orderId, refundAmountCents, actual);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    public List<MemberLevelRuleDto> levelRulesActive() {
        return levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE").stream()
                .map(r -> new MemberLevelRuleDto(
                        r.getId(),
                        r.getLevelCode(),
                        r.getLevelName(),
                        r.getMinSpent(),
                        r.getMaxSpent(),
                        r.getMinPoints() != null ? r.getMinPoints() : 0,
                        r.getMaxPoints(),
                        r.getPointsRate() != null ? r.getPointsRate() : java.math.BigDecimal.ONE,
                        r.getPriceDiscountPct() != null ? r.getPriceDiscountPct() : java.math.BigDecimal.ZERO,
                        r.getSortorder() != null ? r.getSortorder() : 0,
                        r.getStatus()))
                .toList();
    }

    /**
     * 会员价：按等级 {@code priceDiscountPct} 打折（如 5 → 95 折）。无会员/0 折返回原价。
     */
    @Transactional(readOnly = true)
    public int applyMemberPriceDiscount(Long userId, int unitPriceCents) {
        if (userId == null || unitPriceCents <= 0) {
            return Math.max(0, unitPriceCents);
        }
        Member member = memberRepository.findByUserId(userId).orElse(null);
        if (member == null || member.getMemberLevel() == null) {
            return unitPriceCents;
        }
        MemberLevelRule rule = levelRuleRepository.findByLevelCode(member.getMemberLevel()).orElse(null);
        if (rule == null || rule.getPriceDiscountPct() == null
                || rule.getPriceDiscountPct().compareTo(BigDecimal.ZERO) <= 0) {
            return unitPriceCents;
        }
        BigDecimal pct = rule.getPriceDiscountPct().min(new BigDecimal("90"));
        BigDecimal factor = BigDecimal.ONE.subtract(pct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
        int discounted = BigDecimal.valueOf(unitPriceCents).multiply(factor)
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(0, Math.min(unitPriceCents, discounted));
    }

    static String memberUserLockKey(long userId) {
        return "member:user:" + userId;
    }

    private <T> T runWithMemberUserLock(long userId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(memberUserLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会员处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(memberUserLockKey(userId));
        }
    }
}
