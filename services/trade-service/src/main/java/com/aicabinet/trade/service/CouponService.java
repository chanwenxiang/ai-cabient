package com.aicabinet.trade.service;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUPON_CODE_LENGTH = 12;

    private final CouponDefinitionRepository definitionRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserInfoRepository userInfoRepository;
    private final CabinetOrderRepository orderRepository;

    public CouponService(CouponDefinitionRepository definitionRepository,
                         UserCouponRepository userCouponRepository,
                         UserInfoRepository userInfoRepository,
                         CabinetOrderRepository orderRepository) {
        this.definitionRepository = definitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.userInfoRepository = userInfoRepository;
        this.orderRepository = orderRepository;
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

    public List<CouponDefinitionDto> listDefinitions() {
        return definitionRepository.findAll().stream().map(this::toDefDto).toList();
    }

    public List<CouponDefinitionDto> listActiveDefinitions() {
        return definitionRepository.findByStatus("ACTIVE").stream().map(this::toDefDto).toList();
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
        return toDto(uc, def.getCouponName());
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
            return toDto(uc, def != null ? def.getCouponName() : "未知优惠券");
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
        int discount = resolveDiscount(def);

        uc.setStatus("USED");
        uc.setUsedAt(Instant.now());
        uc.setOrderId(orderId);
        uc.setDeviceId(deviceId);
        uc.setDiscountCents(discount);
        userCouponRepository.save(uc);

        log.info("coupon used userId={} couponId={} order={} discount={}", userId, couponId, orderId, discount);
        return toDto(uc, def.getCouponName());
    }

    public int resolveDiscount(CouponDefinition def) {
        return switch (def.getCouponType()) {
            case "AMOUNT_OFF" -> def.getDenominationCents();
            case "PERCENT_OFF" -> 0;
            default -> def.getDenominationCents();
        };
    }

    // ── 定时任务：过期处理 ──────────────────────────────

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void expireOverdueCoupons() {
        List<UserCoupon> expired = userCouponRepository.findByStatusAndExpireAtBefore("UNUSED", Instant.now());
        for (UserCoupon uc : expired) {
            uc.setStatus("EXPIRED");
        }
        userCouponRepository.saveAll(expired);
        if (!expired.isEmpty()) {
            log.info("expired {} overdue coupons", expired.size());
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

    private CouponDto toDto(UserCoupon uc, String couponName) {
        return new CouponDto(
                uc.getCouponId(), couponName, uc.getStatus(),
                uc.getDiscountCents() != null ? uc.getDiscountCents() : 0,
                0, uc.getStatus(), uc.getExpireAt(),
                uc.getReceivedAt(), uc.getUsedAt(), uc.getCouponCode());
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

