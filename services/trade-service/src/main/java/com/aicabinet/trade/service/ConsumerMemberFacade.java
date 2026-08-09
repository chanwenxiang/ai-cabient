package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.common.dto.MemberProfileDto;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
public class ConsumerMemberFacade {

    private final MemberService memberService;
    private final MemberLevelRuleMapper levelRuleRepository;

    public ConsumerMemberFacade(MemberService memberService, MemberLevelRuleMapper levelRuleRepository) {
        this.memberService = memberService;
        this.levelRuleRepository = levelRuleRepository;
    }

    @Transactional
    public MemberProfileDto profile(Long userId) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        List<MemberLevelRule> rules = levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE");
        MemberLevelRule current = rules.stream()
                .filter(r -> r.getLevelCode().equals(member.getMemberLevel()))
                .findFirst()
                .orElse(rules.isEmpty() ? null : rules.get(0));
        MemberLevelRule next = rules.stream()
                .filter(r -> current != null && r.getSortorder() > current.getSortorder())
                .min(Comparator.comparing(MemberLevelRule::getSortorder))
                .orElse(null);

        int spentToNext = 0;
        String nextName = null;
        double progress = 100.0;
        if (next != null && next.getMinSpent() != null) {
            BigDecimal spent = member.getTotalSpent() != null ? member.getTotalSpent() : BigDecimal.ZERO;
            BigDecimal floor = current != null && current.getMinSpent() != null ? current.getMinSpent() : BigDecimal.ZERO;
            BigDecimal ceiling = next.getMinSpent();
            BigDecimal span = ceiling.subtract(floor);
            BigDecimal gained = spent.subtract(floor).max(BigDecimal.ZERO);
            if (span.compareTo(BigDecimal.ZERO) > 0) {
                progress = gained.multiply(BigDecimal.valueOf(100))
                        .divide(span, 1, RoundingMode.HALF_UP)
                        .min(BigDecimal.valueOf(100))
                        .doubleValue();
            }
            spentToNext = Math.max(0, ceiling.subtract(spent).setScale(0, RoundingMode.CEILING).intValue());
            nextName = next.getLevelName();
        }

        return new MemberProfileDto(
                member.getMemberId(),
                member.getUserId(),
                member.getMemberLevel(),
                current != null ? current.getLevelName() : member.getMemberLevel(),
                member.getTotalSpent() != null ? member.getTotalSpent() : BigDecimal.ZERO,
                nz(member.getAvailablePoints()),
                nz(member.getTotalPoints()),
                nz(member.getOrderCount()),
                spentToNext,
                nextName,
                progress,
                rules.stream().map(this::toLevelDto).toList(),
                member.getCreatedAt()
        );
    }

    private MemberLevelRuleDto toLevelDto(MemberLevelRule r) {
        return new MemberLevelRuleDto(
                r.getId(),
                r.getLevelCode(),
                r.getLevelName(),
                r.getMinSpent(),
                r.getMaxSpent(),
                r.getMinPoints() != null ? r.getMinPoints() : 0,
                r.getMaxPoints(),
                r.getPointsRate() != null ? r.getPointsRate() : BigDecimal.ONE,
                r.getSortorder() != null ? r.getSortorder() : 0,
                r.getStatus()
        );
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
