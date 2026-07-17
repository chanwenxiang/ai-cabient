package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MemberService {
    private static final Logger log = LoggerFactory.getLogger(MemberService.class);
    
    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_SILVER = "SILVER";
    public static final String LEVEL_GOLD = "GOLD";
    public static final String LEVEL_PLATINUM = "PLATINUM";
    
    public static final String POINTS_EARN = "EARN";
    public static final String POINTS_USE = "USE";
    public static final String POINTS_EXPIRE = "EXPIRE";
    
    @Autowired
    private MemberMapper memberRepository;
    
    @Autowired
    private MemberPointsLogMapper pointsLogRepository;
    
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
        member.setTotalPoints(0);
        member.setAvailablePoints(0);
        member.setUsedPoints(0);
        member.setExpiredPoints(0);
        member.setTotalSpent(BigDecimal.ZERO);
        member.setOrderCount(0);
        member.setInviteCode(generateInviteCode());
        member.setCreatedAt(Instant.now());
        
        return memberRepository.save(member);
    }
    
    @Transactional
    public int earnPoints(Long memberId, Integer points, String sourceType, String sourceId, String description) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null || points <= 0) {
            return 0;
        }
        
        Instant expireAt = Instant.now().plus(365, ChronoUnit.DAYS);
        
        MemberPointsLog log = new MemberPointsLog();
        log.setMemberId(memberId);
        log.setPoints(points);
        log.setPointsType(POINTS_EARN);
        log.setSourceType(sourceType);
        log.setSourceId(sourceId);
        log.setDescription(description);
        log.setCreatedAt(Instant.now());
        log.setExpireAt(expireAt);
        pointsLogRepository.save(log);
        
        member.setTotalPoints(member.getTotalPoints() + points);
        member.setAvailablePoints(member.getAvailablePoints() + points);
        memberRepository.save(member);
        
        this.log.info("Points earned: memberId={}, points={}", memberId, points);
        return points;
    }
    
    @Transactional
    public boolean usePoints(Long memberId, Integer points, String sourceType, String sourceId, String description) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null || points <= 0) {
            return false;
        }
        
        if (member.getAvailablePoints() < points) {
            return false;
        }
        
        MemberPointsLog log = new MemberPointsLog();
        log.setMemberId(memberId);
        log.setPoints(-points);
        log.setPointsType(POINTS_USE);
        log.setSourceType(sourceType);
        log.setSourceId(sourceId);
        log.setDescription(description);
        log.setCreatedAt(Instant.now());
        pointsLogRepository.save(log);
        
        member.setAvailablePoints(member.getAvailablePoints() - points);
        member.setUsedPoints(member.getUsedPoints() + points);
        memberRepository.save(member);
        
        this.log.info("Points used: memberId={}, points={}", memberId, points);
        return true;
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
            this.log.info("Member level upgraded: memberId={}, newLevel={}", memberId, newLevel);
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
    
    @Transactional
    public int expirePoints() {
        List<Member> members = memberRepository.findAll();
        int totalExpired = 0;
        
        for (Member member : members) {
            List<MemberPointsLog> expiredLogs = pointsLogRepository
                .findExpiredPoints(member.getMemberId(), Instant.now());
            
            for (MemberPointsLog log : expiredLogs) {
                if (log.getPoints() > 0) {
                    member.setAvailablePoints(member.getAvailablePoints() - log.getPoints());
                    member.setExpiredPoints(member.getExpiredPoints() + log.getPoints());
                    
                    MemberPointsLog expireLog = new MemberPointsLog();
                    expireLog.setMemberId(member.getMemberId());
                    expireLog.setPoints(-log.getPoints());
                    expireLog.setPointsType(POINTS_EXPIRE);
                    expireLog.setDescription("Points expired");
                    expireLog.setCreatedAt(Instant.now());
                    pointsLogRepository.save(expireLog);
                    
                    totalExpired += log.getPoints();
                }
            }
            
            memberRepository.save(member);
        }
        
        this.log.info("Points expired: total={}", totalExpired);
        return totalExpired;
    }
    
    private String generateInviteCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public Optional<Member> getMemberByUserId(Long userId) {
        return memberRepository.findByUserId(userId);
    }
    
    public Optional<Member> getMember(Long memberId) {
        return memberRepository.findById(memberId);
    }
    
    public List<MemberPointsLog> getPointsHistory(Long memberId) {
        return pointsLogRepository.findByMemberId(memberId);
    }

    /**
     * 订单支付成功后：按当前等级倍率返积分，并累计消费升级。
     * 1 元实付 ≈ 1 积分 × points_rate（向下取整）。
     */
    @Transactional
    public int onOrderPaid(Long userId, int paidAmountCents, String orderId) {
        if (userId == null || paidAmountCents <= 0) {
            return 0;
        }
        Member member = getMemberByUserId(userId).orElseGet(() -> createMember(userId));
        double rate = 1.0;
        Optional<MemberLevelRule> rule = levelRuleRepository.findByLevelCode(member.getMemberLevel());
        if (rule.isPresent() && rule.get().getPointsRate() != null) {
            rate = rule.get().getPointsRate().doubleValue();
        }
        int points = (int) Math.floor(paidAmountCents / 100.0 * rate);
        if (points > 0) {
            earnPoints(member.getMemberId(), points, "ORDER", orderId, "购物返积分");
        }
        updateMemberStats(member.getMemberId(), BigDecimal.valueOf(paidAmountCents, 2));
        return points;
    }

    /** 查询订单购物返积分（无记录返回 0）。 */
    @Transactional(readOnly = true)
    public int findOrderEarnPoints(Long userId, String orderId) {
        if (userId == null || orderId == null || orderId.isBlank()) {
            return 0;
        }
        return getMemberByUserId(userId)
                .flatMap(m -> pointsLogRepository.findByMemberIdAndSource(m.getMemberId(), "ORDER", orderId))
                .map(MemberPointsLog::getPoints)
                .map(p -> Math.abs(p == null ? 0 : p))
                .orElse(0);
    }
}
