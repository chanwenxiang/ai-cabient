package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.common.dto.MemberPointsLogDto;
import com.aicabinet.common.dto.MemberPointsSummaryDto;
import com.aicabinet.common.dto.PointsRedeemItemDto;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.domain.PointsRedeemItem;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.PointsRedeemItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class PointsRedeemService {

    private final MemberService memberService;
    private final MemberMapper memberRepository;
    private final MemberPointsLogMapper pointsLogRepository;
    private final PointsRedeemItemMapper redeemItemRepository;
    private final CouponService couponService;
    private final CouponDefinitionMapper couponDefinitionRepository;

    public PointsRedeemService(MemberService memberService,
                               MemberMapper memberRepository,
                               MemberPointsLogMapper pointsLogRepository,
                               PointsRedeemItemMapper redeemItemRepository,
                               CouponService couponService,
                               CouponDefinitionMapper couponDefinitionRepository) {
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.pointsLogRepository = pointsLogRepository;
        this.redeemItemRepository = redeemItemRepository;
        this.couponService = couponService;
        this.couponDefinitionRepository = couponDefinitionRepository;
    }

    @Transactional(readOnly = true)
    public MemberPointsSummaryDto summary(Long userId) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        var rules = memberService.levelRulesActive();
        var current = rules.stream()
                .filter(r -> r.levelCode().equals(member.getMemberLevel()))
                .findFirst().orElse(null);
        var next = rules.stream()
                .filter(r -> r.sortOrder() > (current != null ? current.sortOrder() : 0))
                .min(Comparator.comparingInt(MemberLevelRuleDto::sortOrder))
                .orElse(null);
        int gap = 0;
        if (next != null && next.minPoints() > 0) {
            gap = Math.max(0, next.minPoints() - nz(member.getTotalPoints()));
        }
        return new MemberPointsSummaryDto(
                nz(member.getAvailablePoints()),
                nz(member.getTotalPoints()),
                nz(member.getUsedPoints()),
                nz(member.getExpiredPoints()),
                member.getMemberLevel(),
                current != null ? current.levelName() : member.getMemberLevel(),
                current != null && current.pointsRate() != null ? current.pointsRate() : BigDecimal.ONE,
                gap
        );
    }

    @Transactional(readOnly = true)
    public List<MemberPointsLogDto> pointsLog(Long userId, int limit) {
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        return pointsLogRepository.findByMemberIdOrderByCreatedDesc(member.getMemberId(), limit)
                .stream().map(l -> new MemberPointsLogDto(
                        l.getId(),
                        l.getPoints(),
                        l.getPointsType(),
                        l.getSourceType(),
                        l.getDescription(),
                        l.getCreatedAt(),
                        l.getExpireAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PointsRedeemItemDto> redeemItems() {
        return redeemItemRepository.findActiveOrdered().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<PointsRedeemItemDto> adminList() {
        return redeemItemRepository.findAllOrdered().stream().map(this::toDto).toList();
    }

    @Transactional
    public CouponDto redeem(Long userId, Long itemId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Member member = memberService.getMemberByUserId(userId)
                .orElseGet(() -> memberService.createMember(userId));
        PointsRedeemItem item = redeemItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换商品不存在"));
        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该兑换已下架");
        }
        if (item.getStockTotal() - nz(item.getRedeemedCount()) <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该兑换已兑完");
        }
        int cost = item.getPointsCost() == null ? 0 : item.getPointsCost();
        if (nz(member.getAvailablePoints()) < cost) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "积分不足");
        }

        CouponDto coupon = couponService.issueToUser(userId, item.getCouponDefId());

        member.setAvailablePoints(nz(member.getAvailablePoints()) - cost);
        member.setUsedPoints(nz(member.getUsedPoints()) + cost);
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);

        MemberPointsLog log = new MemberPointsLog();
        log.setMemberId(member.getMemberId());
        log.setPoints(-cost);
        log.setPointsType("USE");
        log.setSourceType("REDEEM");
        log.setSourceId("REDEEM-" + itemId);
        log.setDescription("兑换" + item.getTitle());
        log.setExpireAt(Instant.now().plus(365, ChronoUnit.DAYS));
        pointsLogRepository.save(log);

        item.setRedeemedCount(nz(item.getRedeemedCount()) + 1);
        item.setUpdatedAt(Instant.now());
        redeemItemRepository.save(item);
        return coupon;
    }

    @Transactional
    public PointsRedeemItemDto adminUpsert(PointsRedeemItemDto dto) {
        PointsRedeemItem item = dto.itemId() != null
                ? redeemItemRepository.findById(dto.itemId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换项不存在"))
                : new PointsRedeemItem();
        item.setTitle(dto.title());
        item.setSubtitle(dto.subtitle());
        item.setCoverEmoji(dto.coverEmoji() == null ? "馃巵" : dto.coverEmoji());
        item.setPointsCost(dto.pointsCost());
        item.setCouponDefId(dto.couponDefId());
        item.setStockTotal(dto.stockTotal());
        item.setSortOrder(dto.sortOrder());
        item.setStatus(dto.status() == null ? "ACTIVE" : dto.status());
        item.setUpdatedAt(Instant.now());
        return toDto(redeemItemRepository.save(item));
    }

    @Transactional
    public PointsRedeemItemDto adminSetStatus(Long itemId, String status) {
        PointsRedeemItem item = redeemItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换项不存在"));
        item.setStatus("ACTIVE".equalsIgnoreCase(status) ? "ACTIVE" : "INACTIVE");
        item.setUpdatedAt(Instant.now());
        return toDto(redeemItemRepository.save(item));
    }

    private PointsRedeemItemDto toDto(PointsRedeemItem item) {
        String couponName = null;
        Integer denomination = null;
        Integer minSpend = null;
        Integer validityDays = null;
        String deviceScope = null;
        Optional<CouponDefinition> def = item.getCouponDefId() == null
                ? Optional.empty()
                : couponDefinitionRepository.findById(item.getCouponDefId());
        if (def.isPresent()) {
            CouponDefinition d = def.get();
            couponName = d.getCouponName();
            denomination = d.getDenominationCents();
            minSpend = d.getMinSpendCents();
            validityDays = d.getValidityDays();
            deviceScope = d.getDeviceScope();
        }
        return new PointsRedeemItemDto(
                item.getItemId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getCoverEmoji(),
                nz(item.getPointsCost()),
                item.getCouponDefId(),
                couponName,
                nz(item.getStockTotal()),
                nz(item.getRedeemedCount()),
                Math.max(0, nz(item.getStockTotal()) - nz(item.getRedeemedCount())),
                nz(item.getSortOrder()),
                item.getStatus(),
                item.getCreatedAt(),
                denomination,
                minSpend,
                validityDays,
                deviceScope
        );
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
