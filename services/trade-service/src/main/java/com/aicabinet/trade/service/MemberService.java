package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.common.dto.MemberLevelRuleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    @Autowired
    private MemberMapper memberRepository;

    @Autowired
    private MemberLevelRuleMapper levelRuleRepository;

    @Autowired
    private MemberPointsLogMapper pointsLogRepository;

    @Autowired
    private DistributedLockService distributedLockService;

    @Autowired
    @Lazy
    private MemberService self;

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
        member.setOrderCount(member.getOrderCount() + 1);

        String newLevel = calculateMemberLevel(member.getTotalSpent());
        if (!newLevel.equals(member.getMemberLevel())) {
            member.setMemberLevel(newLevel);
            member.setLevelUpgradeAt(Instant.now());
            log.info("Member level upgraded: memberId={}, newLevel={}", member.getMemberId(), newLevel);
        }

        member.setUpdatedAt(Instant.now());
    }

    private String calculateMemberLevel(BigDecimal totalSpent) {
        List<MemberLevelRule> rules = levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE");

        for (MemberLevelRule rule : rules) {
            if (rule.getMinSpent() != null && totalSpent.compareTo(rule.getMinSpent()) >= 0) {
                if (rule.getMaxSpent() == null || totalSpent.compareTo(rule.getMaxSpent()) < 0) {
                    return rule.getLevelCode();
                }
            }
        }

        return LEVEL_NORMAL;
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

        MemberPointsLog log = new MemberPointsLog();
        log.setMemberId(member.getMemberId());
        log.setPoints(points);
        log.setPointsType("EARN");
        log.setSourceType("ORDER");
        log.setSourceId(sourceId);
        log.setDescription("购物返积分");
        log.setExpireAt(Instant.now().plus(365, ChronoUnit.DAYS));
        pointsLogRepository.save(log);
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
