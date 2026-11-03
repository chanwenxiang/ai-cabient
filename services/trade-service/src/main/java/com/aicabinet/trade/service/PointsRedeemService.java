package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

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
    private final DistributedLockService distributedLockService;
    private final AdminAuditService auditService;

    public PointsRedeemService(MemberService memberService,
                               MemberMapper memberRepository,
                               MemberPointsLogMapper pointsLogRepository,
                               PointsRedeemItemMapper redeemItemRepository,
                               CouponService couponService,
                               CouponDefinitionMapper couponDefinitionRepository,
                               DistributedLockService distributedLockService,
                               AdminAuditService auditService) {
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.pointsLogRepository = pointsLogRepository;
        this.redeemItemRepository = redeemItemRepository;
        this.couponService = couponService;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.distributedLockService = distributedLockService;
        this.auditService = auditService;
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
        if (!distributedLockService.tryLock(MemberService.memberUserLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会员处理中，请稍后重试");
        }
        String lockKey = "redeem:item:" + itemId + ":user:" + userId;
        if (!distributedLockService.tryLock(lockKey, 30, 3)) {
            distributedLockService.unlock(MemberService.memberUserLockKey(userId));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "兑换繁忙，请稍后重试");
        }
        try {
            if (memberService.getMemberByUserId(userId).isEmpty()) {
                memberService.createMember(userId);
            }
            Member member = memberRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会员不存在"));
            PointsRedeemItem item = redeemItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换商品不存在"));
            if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(item.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该兑换已下架");
            }
            int cost = item.getPointsCost() == null ? 0 : item.getPointsCost();
            if (nz(member.getAvailablePoints()) < cost) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "积分不足");
            }
            if (redeemItemRepository.tryClaimStock(itemId) <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该兑换已兑完");
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
            return coupon;
        } finally {
            distributedLockService.unlock(lockKey);
            distributedLockService.unlock(MemberService.memberUserLockKey(userId));
        }
    }

    /**
     * 兑换项增改（运营台）。
     *
     * <p>MA-096：这些字段是**积分 → 可兑资产**的兑换率，原来原样落库零校验：
     * {@code pointsCost} 为负时，兑换判定 {@code available < cost} 恒假，扣积分那段
     * 变成 {@code available - (负数)} ⇒ **每兑一次给会员倒增积分**（可反复刷 = 持续铸分）；
     * {@code stockTotal} 为负让库存口径失真；指向不存在/已停用的券模板则整项一兑就 400/409。
     */
    @Transactional
    public PointsRedeemItemDto adminUpsert(Long operatorId, PointsRedeemItemDto dto) {
        requireRedeemParams(dto);
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
        item.setStatus(requireRedeemStatus(dto.status()));
        item.setUpdatedAt(Instant.now());
        PointsRedeemItem saved = redeemItemRepository.save(item);
        auditService.appendLog(operatorId, "POINTS_REDEEM_UPSERT", "POINTS_REDEEM_ITEM",
                String.valueOf(saved.getItemId()),
                "title=" + saved.getTitle() + ",pointsCost=" + saved.getPointsCost()
                        + ",couponDefId=" + saved.getCouponDefId()
                        + ",stockTotal=" + saved.getStockTotal() + ",status=" + saved.getStatus());
        return toDto(saved);
    }

    /**
     * MA-096：兑换率与库存的写侧闸（积分为负 = 兑换即铸分）。
     * 判定用 {@code available < cost}，负 cost 让这个门恒假，随后
     * {@code available - cost} 反而给会员**倒增**积分，可反复刷。
     */
    private void requireRedeemParams(PointsRedeemItemDto dto) {
        if (dto.title() == null || dto.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 必填");
        }
        if (dto.pointsCost() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pointsCost 不得为负（负数会让兑换时倒增积分 = 持续铸分）");
        }
        if (dto.stockTotal() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stockTotal 不得为负");
        }
        if (dto.sortOrder() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sortOrder 不得为负");
        }
        if (dto.couponDefId() == null) {
            // 列本就 NOT NULL（V163），缺它只会撞成没有字段名的泛化 409
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "couponDefId 必填（兑换发放的券模板）");
        }
        CouponDefinition def = couponDefinitionRepository.findById(dto.couponDefId()).orElse(null);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券模板不存在：" + dto.couponDefId());
        }
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "券模板当前状态是 " + def.getStatus() + "，不能挂成在架兑换项（用户一点兑换就失败）");
        }
    }

    /** 状态词表与券模板同源（ACTIVE/INACTIVE）；写错的值会让兑换项静默下线。 */
    private static String requireRedeemStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return CabinetConstants.PROMOTION_STATUS_ACTIVE;
        }
        String status = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(status) && !"INACTIVE".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只能是 ACTIVE/INACTIVE");
        }
        return status;
    }

    @Transactional
    public PointsRedeemItemDto adminSetStatus(Long operatorId, Long itemId, String status) {
        PointsRedeemItem item = redeemItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "兑换项不存在"));
        item.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(status) ? CabinetConstants.PROMOTION_STATUS_ACTIVE : "INACTIVE");
        item.setUpdatedAt(Instant.now());
        PointsRedeemItem saved = redeemItemRepository.save(item);
        auditService.appendLog(operatorId, "POINTS_REDEEM_STATUS", "POINTS_REDEEM_ITEM",
                String.valueOf(saved.getItemId()), "status=" + saved.getStatus());
        return toDto(saved);
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
