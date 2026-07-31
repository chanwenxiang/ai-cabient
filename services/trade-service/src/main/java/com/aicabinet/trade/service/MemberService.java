package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
    }
}
