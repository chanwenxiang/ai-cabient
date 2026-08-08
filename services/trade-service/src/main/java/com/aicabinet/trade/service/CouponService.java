package com.aicabinet.trade.service;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CouponService {
    @Autowired
    private ScheduledTaskService taskService;

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUPON_CODE_LENGTH = 12;

    private final CouponDefinitionMapper definitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final UserInfoMapper userInfoRepository;

    public CouponService(CouponDefinitionMapper definitionRepository,
                         UserCouponMapper userCouponRepository,
                         UserInfoMapper userInfoRepository) {
        this.definitionRepository = definitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.userInfoRepository = userInfoRepository;
    }

    // ── 优惠券定义管理 ─────────────────────────────────

    @Transactional
    public CouponDefinitionDto createDefinition(CreateCouponRequest request) {
        CouponDefinition def = new CouponDefinition();
        def.setCouponName(request.couponName());
        def.setCouponType(request.couponType());
        def.setDenominationCents(request.denominationCents());
        def.setMinSpendCents(request.minSpendCents());
        def.setDiscountPercent(request.discountPercent());
        def.setValidityDays(request.validityDays());
        def.setMaxIssueCount(request.maxIssueCount());
        def.setDescription(request.description());
        def.setStatus("ACTIVE");
        definitionRepository.save(def);
        log.info("coupon definition created id={} name={}", def.getCouponDefId(), def.getCouponName());
        return toDefDto(def);
    }

    @Transactional
    public CouponDefinitionDto updateDefinition(Long couponDefId, UpdateCouponRequest request) {
        CouponDefinition def = definitionRepository.findById(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券定义不存在"));
        def.setCouponName(request.couponName());
        def.setCouponType(request.couponType());
        def.setDenominationCents(request.denominationCents());
        def.setMinSpendCents(request.minSpendCents());
        def.setDiscountPercent(request.discountPercent());
        def.setValidityDays(request.validityDays());
        def.setMaxIssueCount(request.maxIssueCount());
        def.setDescription(request.description());
        definitionRepository.save(def);
        log.info("coupon definition updated id={} name={}", def.getCouponDefId(), def.getCouponName());
        return toDefDto(def);
    }

    public List<CouponDefinitionDto> listDefinitions() {
        return definitionRepository.findAll().stream().map(this::toDefDto).toList();
    }

    public List<CouponDefinitionDto> listActiveDefinitions() {
        return definitionRepository.findByStatus("ACTIVE").stream().map(this::toDefDto).toList();
    }

    @Transactional
    public CouponDefinitionDto setDefinitionStatus(Long couponDefId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!"ACTIVE".equals(normalized) && !"INACTIVE".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态仅支持 ACTIVE 或 INACTIVE");
        }
        CouponDefinition def = definitionRepository.findById(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券定义不存在"));
        def.setStatus(normalized);
        definitionRepository.save(def);
        log.info("coupon definition status id={} status={}", couponDefId, normalized);
        return toDefDto(def);
    }

    // ── 发券 ────────────────────────────────────────────

    @Transactional
    public CouponDto issueToUser(Long userId, Long couponDefId) {
        CouponDefinition def = definitionRepository.findById(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券定义不存在"));
        if (!"ACTIVE".equals(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠券已停用");
        }
        if (def.getMaxIssueCount() > 0 && def.getIssuedCount() >= def.getMaxIssueCount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已发完");
        }
        userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));

        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponDefId(couponDefId);
        uc.setCouponCode(generateCouponCode());
        uc.setExpireAt(Instant.now().plus(def.getValidityDays(), ChronoUnit.DAYS));
        uc.setStatus("UNUSED");
        userCouponRepository.save(uc);

        def.setIssuedCount(def.getIssuedCount() + 1);
        definitionRepository.save(def);

        log.info("coupon issued userId={} couponId={} code={}", userId, uc.getCouponId(), uc.getCouponCode());
        return toDto(uc, def);
    }

    @Transactional
    public List<CouponDto> batchIssue(Long couponDefId, List<Long> userIds) {
        return userIds.stream().map(uid -> issueToUser(uid, couponDefId)).toList();
    }

    // ── 用户查询 ────────────────────────────────────────

    public List<CouponDto> listUserCoupons(Long userId, String statusFilter) {
        List<UserCoupon> coupons = (statusFilter != null && !statusFilter.isBlank())
                ? userCouponRepository.findByUserIdAndStatus(userId, statusFilter)
                : userCouponRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return coupons.stream().map(uc -> {
            CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
            return toDto(uc, def);
        }).toList();
    }

    public long countAvailable(Long userId) {
        return userCouponRepository.countByUserIdAndStatus(userId, "UNUSED");
    }

    // ── 核销 ────────────────────────────────────────────

    @Transactional
    public CouponDto useCoupon(Long userId, Long couponId, String orderId, String deviceId) {
        UserCoupon uc = userCouponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券不存在"));
        if (!uc.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该优惠券");
        }
        if (!"UNUSED".equals(uc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已使用或已过期");
        }
        if (uc.getExpireAt().isBefore(Instant.now())) {
            uc.setStatus("EXPIRED");
            userCouponRepository.save(uc);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已过期");
        }

        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券定义不存在"));
        int discount = resolveDiscount(def, Integer.MAX_VALUE);

        uc.setStatus("USED");
        uc.setUsedAt(Instant.now());
        uc.setOrderId(orderId);
        uc.setDeviceId(deviceId);
        uc.setDiscountCents(discount);
        userCouponRepository.save(uc);

        log.info("coupon used userId={} couponId={} order={} discount={}", userId, couponId, orderId, discount);
        return toDto(uc, def);
    }

    public int resolveDiscount(CouponDefinition def) {
        return resolveDiscount(def, Integer.MAX_VALUE);
    }

    public int resolveDiscount(CouponDefinition def, int subtotalCents) {
        if (def == null) return 0;
        String type = def.getCouponType() == null ? "" : def.getCouponType();
        // 无订单上下文时：满减取面额，折扣券无法计算
        if (subtotalCents == Integer.MAX_VALUE) {
            return "PERCENT_OFF".equals(type) ? 0 : Math.max(0, def.getDenominationCents());
        }
        int cappedSubtotal = Math.max(0, subtotalCents);
        int raw = switch (type) {
            case "AMOUNT_OFF" -> def.getDenominationCents();
            case "PERCENT_OFF" -> {
                int pct = def.getDiscountPercent() != null ? def.getDiscountPercent() : 0;
                yield cappedSubtotal * pct / 100;
            }
            default -> def.getDenominationCents();
        };
        return Math.max(0, Math.min(raw, cappedSubtotal));
    }

    /**
     * 自动挑选当前订单可用且抵扣最大的 UNUSED 优惠券。
     */
    public Optional<BestCoupon> selectBestCoupon(Long userId, int subtotalCents) {
        if (userId == null || subtotalCents <= 0) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        BestCoupon best = null;
        for (UserCoupon uc : userCouponRepository.findByUserIdAndStatus(userId, "UNUSED")) {
            if (uc.getExpireAt() != null && uc.getExpireAt().isBefore(now)) {
                continue;
            }
            CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
            if (def == null || !"ACTIVE".equalsIgnoreCase(def.getStatus())) {
                continue;
            }
            if (subtotalCents < def.getMinSpendCents()) {
                continue;
            }
            int discount = resolveDiscount(def, subtotalCents);
            if (discount <= 0) {
                continue;
            }
            if (best == null || discount > best.discountCents()) {
                best = new BestCoupon(uc.getCouponId(), discount, def.getCouponName());
            }
        }
        return Optional.ofNullable(best);
    }

    @Transactional
    public void markUsed(Long userId, Long couponId, String orderId, String deviceId, int discountCents) {
        UserCoupon uc = userCouponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券不存在"));
        if (!uc.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该优惠券");
        }
        if (!"UNUSED".equals(uc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已使用或已过期");
        }
        if (uc.getExpireAt() != null && uc.getExpireAt().isBefore(Instant.now())) {
            uc.setStatus("EXPIRED");
            userCouponRepository.save(uc);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已过期");
        }
        uc.setStatus("USED");
        uc.setUsedAt(Instant.now());
        uc.setOrderId(orderId);
        uc.setDeviceId(deviceId);
        uc.setDiscountCents(Math.max(0, discountCents));
        userCouponRepository.save(uc);
        log.info("coupon marked used userId={} couponId={} order={} discount={}",
                userId, couponId, orderId, discountCents);
    }

    public record BestCoupon(Long couponId, int discountCents, String couponName) {}

    // ── 定时任务：过期处理 ──────────────────────────────

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void expireOverdueCoupons() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("coupon-expire", 600)) {
            return;
        }
        boolean failed = false;
        try {
            List<UserCoupon> expired = userCouponRepository.findByStatusAndExpireAtBefore("UNUSED", Instant.now());
            for (UserCoupon uc : expired) {
                uc.setStatus("EXPIRED");
            }
            userCouponRepository.saveAll(expired);
            if (!expired.isEmpty()) {
                log.info("expired {} overdue coupons", expired.size());
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("coupon-expire", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("coupon-expire", "SUCCESS", null, start);
            }
        }
    }

    // ── DTO ──────────────────────────────────────────────

    private CouponDefinitionDto toDefDto(CouponDefinition d) {
        return new CouponDefinitionDto(
                d.getCouponDefId(), d.getCouponName(), d.getCouponType(),
                d.getDenominationCents(), d.getMinSpendCents(), d.getDiscountPercent(),
                d.getValidityDays(), d.getMaxIssueCount(), d.getIssuedCount(),
                d.getStatus(), d.getDescription());
    }

    private CouponDto toDto(UserCoupon uc, CouponDefinition def) {
        String name = def != null ? def.getCouponName() : "未知优惠券";
        String type = def != null ? def.getCouponType() : "AMOUNT_OFF";
        int denomination = def != null
                ? def.getDenominationCents()
                : (uc.getDiscountCents() != null ? uc.getDiscountCents() : 0);
        int minSpend = def != null ? def.getMinSpendCents() : 0;
        return new CouponDto(
                uc.getCouponId(),
                name,
                type,
                denomination,
                minSpend,
                uc.getStatus(),
                uc.getExpireAt(),
                uc.getReceivedAt(),
                uc.getUsedAt(),
                uc.getCouponCode());
    }

    private String generateCouponCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(COUPON_CODE_LENGTH);
        for (int i = 0; i < COUPON_CODE_LENGTH; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
