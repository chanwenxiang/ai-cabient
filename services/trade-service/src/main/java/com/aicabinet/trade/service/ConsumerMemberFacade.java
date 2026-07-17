package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.PointsRedeemItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
public class ConsumerMemberFacade {

    private final MemberService memberService;
    private final MemberPointsLogMapper pointsLogRepository;
    private final MemberLevelRuleMapper levelRuleRepository;
    private final PointsRedeemItemMapper redeemItemRepository;
    private final CouponService couponService;
    private final CouponDefinitionMapper couponDefinitionMapper;

    public ConsumerMemberFacade(
            MemberService memberService,
            MemberPointsLogMapper pointsLogRepository,
            MemberLevelRuleMapper levelRuleRepository,
            PointsRedeemItemMapper redeemItemRepository,
            CouponService couponService,
            CouponDefinitionMapper couponDefinitionMapper) {
        this.memberService = memberService;
        this.pointsLogRepository = pointsLogRepository;
        this.levelRuleRepository = levelRuleRepository;
        this.redeemItemRepository = redeemItemRepository;
        this.couponService = couponService;
        this.couponDefinitionMapper = couponDefinitionMapper;
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

        int pointsToNext = 0;
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
            // 前端展示「距下一等级」用消费差额折算为积分感：按 1 元 ≈ 1 积分进度提示
            pointsToNext = Math.max(0, ceiling.subtract(spent).setScale(0, RoundingMode.CEILING).intValue());
            nextName = next.getLevelName();
        }

        double rate = current != null && current.getPointsRate() != null
                ? current.getPointsRate().doubleValue() : 1.0;

        return new MemberProfileDto(
                member.getMemberId(),
                member.getUserId(),
                member.getMemberLevel(),
                current != null ? current.getLevelName() : member.getMemberLevel(),
                nz(member.getAvailablePoints()),
                nz(member.getTotalPoints()),
                nz(member.getUsedPoints()),
                member.getTotalSpent() != null ? member.getTotalSpent() : BigDecimal.ZERO,
                nz(member.getOrderCount()),
                member.getInviteCode(),
                pointsToNext,
                nextName,
                progress,
                rate,
                rules.stream().map(this::toLevelDto).toList(),
                member.getCreatedAt()
        );
    }

    public MemberPointsSummaryDto pointsSummary(Long userId) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        Instant monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<MemberPointsLog> logs = pointsLogRepository.findByMemberId(member.getMemberId());
        int earnedThisMonth = logs.stream()
                .filter(l -> MemberService.POINTS_EARN.equals(l.getPointsType()))
                .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(monthStart))
                .mapToInt(l -> Math.max(0, l.getPoints()))
                .sum();
        int usedThisMonth = logs.stream()
                .filter(l -> MemberService.POINTS_USE.equals(l.getPointsType()))
                .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(monthStart))
                .mapToInt(l -> Math.abs(l.getPoints()))
                .sum();
        return new MemberPointsSummaryDto(
                nz(member.getAvailablePoints()),
                nz(member.getTotalPoints()),
                nz(member.getUsedPoints()),
                nz(member.getExpiredPoints()),
                earnedThisMonth,
                usedThisMonth
        );
    }

    public List<MemberPointsLogDto> pointsHistory(Long userId, String typeFilter) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        List<MemberPointsLog> logs = (typeFilter != null && !typeFilter.isBlank())
                ? pointsLogRepository.findByMemberIdAndPointsType(member.getMemberId(), typeFilter.trim().toUpperCase())
                : pointsLogRepository.findByMemberId(member.getMemberId());
        return logs.stream()
                .sorted(Comparator.comparing(MemberPointsLog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(l -> new MemberPointsLogDto(
                        l.getId(),
                        l.getPoints(),
                        l.getPointsType(),
                        l.getSourceType(),
                        l.getSourceId(),
                        l.getDescription(),
                        l.getCreatedAt(),
                        l.getExpireAt()
                ))
                .toList();
    }

    public List<PointsRedeemItemDto> listRedeemItems(Long userId) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        int available = nz(member.getAvailablePoints());
        return redeemItemRepository.findActiveOrdered().stream()
                .map(item -> toRedeemDto(item, available))
                .toList();
    }

    @Transactional
    public CouponDto redeem(Long userId, Long itemId) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        PointsRedeemItem item = redeemItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换商品不存在"));
        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该兑换已下架");
        }
        int stockLeft = Math.max(0, item.getStockTotal() - item.getRedeemedCount());
        if (stockLeft <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已兑完，下周再来");
        }
        if (nz(member.getAvailablePoints()) < item.getPointsCost()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "积分不足");
        }
        boolean ok = memberService.usePoints(
                member.getMemberId(),
                item.getPointsCost(),
                "REDEEM",
                String.valueOf(item.getItemId()),
                "积分兑换：" + item.getTitle()
        );
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "积分不足");
        }
        CouponDto coupon = couponService.issueToUser(userId, item.getCouponDefId());
        item.setRedeemedCount(item.getRedeemedCount() + 1);
        item.setUpdatedAt(Instant.now());
        redeemItemRepository.save(item);
        return coupon;
    }

    private PointsRedeemItemDto toRedeemDto(PointsRedeemItem item, int availablePoints) {
        CouponDefinition def = couponDefinitionMapper.findById(item.getCouponDefId()).orElse(null);
        int stockLeft = Math.max(0, item.getStockTotal() - item.getRedeemedCount());
        return new PointsRedeemItemDto(
                item.getItemId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getCoverEmoji() != null ? item.getCoverEmoji() : "🎁",
                item.getPointsCost(),
                item.getCouponDefId(),
                def != null ? def.getCouponName() : item.getTitle(),
                def != null ? def.getDenominationCents() : 0,
                def != null ? def.getMinSpendCents() : 0,
                def != null ? def.getCouponType() : "AMOUNT_OFF",
                stockLeft,
                availablePoints >= item.getPointsCost() && stockLeft > 0
        );
    }

    private MemberLevelRuleDto toLevelDto(MemberLevelRule r) {
        return new MemberLevelRuleDto(
                r.getLevelCode(),
                r.getLevelName(),
                r.getMinSpent(),
                r.getMaxSpent(),
                r.getMinPoints() != null ? r.getMinPoints() : 0,
                r.getPointsRate() != null ? r.getPointsRate().doubleValue() : 1.0,
                r.getSortorder() != null ? r.getSortorder() : 0
        );
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
