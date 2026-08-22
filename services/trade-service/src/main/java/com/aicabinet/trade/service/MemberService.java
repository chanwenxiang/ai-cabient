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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Member createMember(Long userId) {
        Member existing = memberRepository.findByUserId(userId).orElse(null);
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
    }

    @Transactional
    public void updateMemberStats(Long memberId, BigDecimal orderAmount) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null) {
            return;
        }

        member.setTotalSpent(member.getTotalSpent().add(orderAmount));
        member.setOrderCount(member.getOrderCount() + 1);

        String newLevel = calculateMemberLevel(member.getTotalSpent());
        if (!newLevel.equals(member.getMemberLevel())) {
            member.setMemberLevel(newLevel);
            member.setLevelUpgradeAt(Instant.now());
            log.info("Member level upgraded: memberId={}, newLevel={}", memberId, newLevel);
        }

        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);
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
        Member member = getMemberByUserId(userId).orElseGet(() -> createMember(userId));
        updateMemberStats(member.getMemberId(), BigDecimal.valueOf(paidAmountCents, 2));
        earnPoints(member, paidAmountCents, orderId);
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
}
