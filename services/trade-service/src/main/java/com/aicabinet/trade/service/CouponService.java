package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class CouponService {
    private static final String COUPON_EXPIRE = "coupon-expire";
    private static final String LITERAL = "优惠券定义不存在";

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUPON_CODE_LENGTH = 12;

    private final ScheduledTaskService taskService;
    private final CouponDefinitionMapper definitionRepository;
    private final UserCouponMapper userCouponRepository;
    private final UserInfoMapper userInfoRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DistributedLockService distributedLockService;
    private final PromotionService promotionService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final CouponService self;

    public CouponService(ScheduledTaskService taskService,
                         CouponDefinitionMapper definitionRepository,
                         UserCouponMapper userCouponRepository,
                         UserInfoMapper userInfoRepository,
                         CabinetOrderMapper orderRepository,
                         CabinetOrderLineMapper orderLineRepository,
                         DistributedLockService distributedLockService,
                         PromotionService promotionService,
                         @Lazy CouponService self) {
        this.taskService = taskService;
        this.definitionRepository = definitionRepository;
        this.userCouponRepository = userCouponRepository;
        this.userInfoRepository = userInfoRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.distributedLockService = distributedLockService;
        this.promotionService = promotionService;
        this.self = self;
    }

    // ── 优惠券定义管理 ─────────────────────────────────

    @Transactional
    public CouponDefinitionDto createDefinition(CreateCouponRequest request) {
        validateDefinitionRequest(
                request.couponName(),
                request.couponType(),
                request.denominationCents(),
                request.minSpendCents(),
                request.discountPercent(),
                request.validityDays(),
                request.maxIssueCount());
        CouponDefinition def = new CouponDefinition();
        def.setCouponName(request.couponName().trim());
        def.setCouponType(request.couponType().trim());
        applyDefinitionAmounts(
                def,
                request.couponType(),
                request.denominationCents(),
                request.minSpendCents(),
                request.discountPercent());
        def.setValidityDays(request.validityDays());
        def.setMaxIssueCount(Math.max(0, request.maxIssueCount()));
        def.setDescription(request.description());
        def.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        definitionRepository.save(def);
        log.info("coupon definition created id={} name={}", def.getCouponDefId(), def.getCouponName());
        return toDefDto(def);
    }

    @Transactional
    public CouponDefinitionDto updateDefinition(Long couponDefId, UpdateCouponRequest request) {
        CouponDefinition def = definitionRepository.findById(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        validateDefinitionRequest(
                request.couponName(),
                request.couponType(),
                request.denominationCents(),
                request.minSpendCents(),
                request.discountPercent(),
                request.validityDays(),
                request.maxIssueCount());
        def.setCouponName(request.couponName().trim());
        def.setCouponType(request.couponType().trim());
        applyDefinitionAmounts(
                def,
                request.couponType(),
                request.denominationCents(),
                request.minSpendCents(),
                request.discountPercent());
        def.setValidityDays(request.validityDays());
        def.setMaxIssueCount(Math.max(0, request.maxIssueCount()));
        def.setDescription(request.description());
        definitionRepository.save(def);
        log.info("coupon definition updated id={} name={}", def.getCouponDefId(), def.getCouponName());
        return toDefDto(def);
    }

    public List<CouponDefinitionDto> listDefinitions() {
        return definitionRepository.findAll().stream().map(this::toDefDto).toList();
    }

    public PageResult<CouponDefinitionDto> listDefinitionsPage(String q, String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = definitionRepository.searchPage(q, status, p, s);
        List<CouponDefinitionDto> items = result.getRecords().stream().map(this::toDefDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    public List<CouponDefinitionDto> listActiveDefinitions() {
        return definitionRepository.findByStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE).stream().map(this::toDefDto).toList();
    }

    @Transactional
    public CouponDefinitionDto setDefinitionStatus(Long couponDefId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(normalized) && !"INACTIVE".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态仅支持 ACTIVE 或 INACTIVE");
        }
        CouponDefinition def = definitionRepository.findById(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        def.setStatus(normalized);
        definitionRepository.save(def);
        log.info("coupon definition status id={} status={}", couponDefId, normalized);
        return toDefDto(def);
    }

    // ── 发券 ────────────────────────────────────────────

    @Transactional
    public CouponDto issueToUser(Long userId, Long couponDefId) {
        String lockKey = "coupon:issue:" + couponDefId;
        if (!distributedLockService.tryLock(lockKey, 30, 3)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发券繁忙，请稍后重试");
        }
        try {
            CouponDefinition def = definitionRepository.findByIdForUpdate(couponDefId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
            if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(def.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠券已停用");
            }
            if (def.getMaxIssueCount() > 0 && userCouponRepository.countByCouponDefId(couponDefId) >= def.getMaxIssueCount()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已发完");
            }
            if (def.getActivityId() != null) {
                promotionService.reserveBudgetOnClaim(
                        def.getActivityId(), PromotionService.budgetReserveCents(def));
            }
            userInfoRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));

            UserCoupon uc = new UserCoupon();
            uc.setUserId(userId);
            uc.setCouponDefId(couponDefId);
            uc.setCouponCode(generateCouponCode());
            uc.setExpireAt(Instant.now().plus(def.getValidityDays(), ChronoUnit.DAYS));
            uc.setStatus(CabinetConstants.COUPON_STATUS_UNUSED);
            userCouponRepository.save(uc);

            long issued = userCouponRepository.countByCouponDefId(couponDefId);
            def.setIssuedCount((int) issued);
            definitionRepository.save(def);

            log.info("coupon issued userId={} couponId={} code={}", userId, uc.getCouponId(), uc.getCouponCode());
            return toDto(uc, def);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    @Transactional
    public List<CouponDto> batchIssue(Long couponDefId, List<Long> userIds) {
        return userIds.stream().map(uid -> self.issueToUser(uid, couponDefId)).toList();
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
        return userCouponRepository.countByUserIdAndStatus(userId, CabinetConstants.COUPON_STATUS_UNUSED);
    }

    // ── 核销 ────────────────────────────────────────────

    @Transactional
    public CouponDto useCoupon(Long userId, Long couponId, String orderId, String deviceId) {
        return runWithCouponUseLock(couponId,
                () -> doUseCoupon(userId, couponId, orderId, deviceId));
    }

    private CouponDto doUseCoupon(Long userId, Long couponId, String orderId, String deviceId) {
        UserCoupon uc = requireValidUserCoupon(userId, couponId);
        CabinetOrder order = requireOrderEligibleForCoupon(userId, orderId, couponId);
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        int discount = requireApplicableDiscount(def, order);
        int subtotal = resolveOrderLineSubtotal(order);

        uc.setStatus("USED");
        uc.setUsedAt(Instant.now());
        uc.setOrderId(order.getOrderId());
        uc.setDeviceId(deviceId != null && !deviceId.isBlank() ? deviceId : order.getDeviceId());
        uc.setDiscountCents(discount);
        userCouponRepository.save(uc);

        reconcilePromotionBudgetOnUse(def, discount);

        order.setCouponId(uc.getCouponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        orderRepository.save(order);

        log.info("coupon used userId={} couponId={} order={} discount={}", userId, couponId, orderId, discount);
        return toDto(uc, def);
    }

    private UserCoupon requireValidUserCoupon(Long userId, Long couponId) {
        UserCoupon uc = userCouponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券不存在"));
        if (!uc.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该优惠券");
        }
        if (!CabinetConstants.COUPON_STATUS_UNUSED.equals(uc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已使用或已过期");
        }
        if (uc.getExpireAt().isBefore(Instant.now())) {
            uc.setStatus(CabinetConstants.COUPON_STATUS_EXPIRED);
            userCouponRepository.save(uc);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已过期");
        }
        return uc;
    }

    private CabinetOrder requireOrderEligibleForCoupon(Long userId, String orderId, Long couponId) {
        if (orderId == null || orderId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少订单号");
        }
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "订单不属于当前用户");
        }
        // 仅允许未支付/争议中订单手动核销；已支付不可再核销改额（BUG-018）
        String status = order.getStatus() == null ? "" : order.getStatus().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "UNPAID", "DISPUTED", "CREATED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不可用券");
        }
        if (order.getCouponId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已使用优惠券");
        }
        for (UserCoupon existing : userCouponRepository.findByOrderIdAndStatus(order.getOrderId(), "USED")) {
            if (!existing.getCouponId().equals(couponId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已核销其他优惠券");
            }
        }
        return order;
    }

    private int requireApplicableDiscount(CouponDefinition def, CabinetOrder order) {
        int subtotal = resolveOrderLineSubtotal(order);
        int minSpend = Math.max(0, def.getMinSpendCents());
        if (subtotal < minSpend) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未满足满减门槛");
        }
        int discount = resolveDiscount(def, subtotal);
        if (discount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠券不可用于该订单");
        }
        return discount;
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
                // long 中间量，避免 subtotal*pct 逼近 int 上限时溢出吞折扣
                yield (int) ((long) cappedSubtotal * pct / 100L);
            }
            default -> def.getDenominationCents();
        };
        return Math.max(0, Math.min(raw, cappedSubtotal));
    }

    /** 争议改单后按已绑券重算抵扣（USED 券仍有效）。 */
    public int discountForOrderCoupon(Long couponId, int subtotalCents) {
        if (couponId == null || subtotalCents <= 0) {
            return 0;
        }
        UserCoupon uc = userCouponRepository.findById(couponId).orElse(null);
        if (uc == null) {
            return 0;
        }
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
        if (def == null || subtotalCents < Math.max(0, def.getMinSpendCents())) {
            return 0;
        }
        return resolveDiscount(def, subtotalCents);
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
        for (UserCoupon uc : userCouponRepository.findByUserIdAndStatus(userId, CabinetConstants.COUPON_STATUS_UNUSED)) {
            Optional<BestCoupon> cand = evaluateCoupon(uc, subtotalCents, now);
            if (cand.isEmpty()) {
                continue;
            }
            BestCoupon c = cand.get();
            if (best == null || c.discountCents() > best.discountCents()) {
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 优先使用指定券；不可用则回退自动择优。 */
    public Optional<BestCoupon> selectPreferredOrBest(Long userId, Long preferredCouponId, int subtotalCents) {
        if (preferredCouponId != null && userId != null && subtotalCents > 0) {
            UserCoupon uc = userCouponRepository.findById(preferredCouponId).orElse(null);
            if (uc != null && userId.equals(uc.getUserId()) && CabinetConstants.COUPON_STATUS_UNUSED.equalsIgnoreCase(uc.getStatus())) {
                Optional<BestCoupon> preferred = evaluateCoupon(uc, subtotalCents, Instant.now());
                if (preferred.isPresent()) {
                    return preferred;
                }
            }
        }
        return selectBestCoupon(userId, subtotalCents);
    }

    private Optional<BestCoupon> evaluateCoupon(UserCoupon uc, int subtotalCents, Instant now) {
        if (uc.getExpireAt() != null && uc.getExpireAt().isBefore(now)) {
            return Optional.empty();
        }
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
        if (def == null || !CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(def.getStatus())) {
            return Optional.empty();
        }
        if (subtotalCents < def.getMinSpendCents()) {
            return Optional.empty();
        }
        int discount = resolveDiscount(def, subtotalCents);
        if (discount <= 0) {
            return Optional.empty();
        }
        return Optional.of(new BestCoupon(uc.getCouponId(), discount, def.getCouponName()));
    }

    /**
     * 部分退后：若剩余金额不满足券门槛则退还券；否则按剩余金额重算抵扣。
     */
    @Transactional
    public void recalcOrRestoreAfterPartialRefund(CabinetOrder order, int remainingSubtotalCents) {
        if (order == null || order.getCouponId() == null) {
            return;
        }
        Long couponId = order.getCouponId();
        UserCoupon uc = userCouponRepository.findById(couponId).orElse(null);
        if (uc == null) {
            order.setCouponId(null);
            order.setCouponDiscountCents(0);
            order.setOriginalAmountCents(Math.max(0, remainingSubtotalCents));
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
        boolean keep = remainingSubtotalCents > 0
                && def != null
                && remainingSubtotalCents >= def.getMinSpendCents();
        if (!keep) {
            if ("USED".equalsIgnoreCase(uc.getStatus())) {
                if (userCouponRepository.restoreUsedToUnused(couponId) > 0) {
                    releasePromotionBudgetIfAny(def);
                    log.info("coupon restored after partial refund order={} couponId={}",
                            order.getOrderId(), couponId);
                }
            }
            order.setCouponId(null);
            order.setCouponDiscountCents(0);
            order.setOriginalAmountCents(Math.max(0, remainingSubtotalCents));
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        int discount = Math.min(resolveDiscount(def, remainingSubtotalCents), remainingSubtotalCents);
        int priorDiscount = Math.max(0, order.getCouponDiscountCents());
        order.setOriginalAmountCents(remainingSubtotalCents);
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, remainingSubtotalCents - discount));
        uc.setDiscountCents(discount);
        userCouponRepository.save(uc);
        if (discount < priorDiscount) {
            reconcilePromotionBudgetDelta(def, priorDiscount - discount);
        }
        log.info("coupon recalculated after partial refund order={} couponId={} discount={}",
                order.getOrderId(), couponId, discount);
    }

    private void releasePromotionBudgetIfAny(CouponDefinition def) {
        if (def == null || def.getActivityId() == null) {
            return;
        }
        promotionService.releaseBudget(def.getActivityId(), PromotionService.budgetReserveCents(def));
    }

    /**
     * 释放订单上错绑的已核销券（订单头无券或券未生效时由一致性修复调用）。
     */
    @Transactional
    public int releaseStaleUsedCouponsForOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return 0;
        }
        List<UserCoupon> used = userCouponRepository.findByOrderIdAndStatus(orderId, "USED");
        for (UserCoupon uc : used) {
            releaseUsedCouponToUnused(uc);
        }
        return used.size();
    }

    private void releaseUsedCouponToUnused(UserCoupon uc) {
        if (uc == null || uc.getCouponId() == null) {
            return;
        }
        if (userCouponRepository.restoreUsedToUnused(uc.getCouponId()) <= 0) {
            return;
        }
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
        releasePromotionBudgetIfAny(def);
        log.info("coupon released from order couponId={}", uc.getCouponId());
    }

    /** 核销时释放领券预留与实际抵扣的差额（如折扣券实抵小于面额）。 */
    private void reconcilePromotionBudgetOnUse(CouponDefinition def, int actualDiscountCents) {
        if (def == null || def.getActivityId() == null) {
            return;
        }
        int reserved = PromotionService.budgetReserveCents(def);
        reconcilePromotionBudgetDelta(def, Math.max(0, reserved - actualDiscountCents));
    }

    private void reconcilePromotionBudgetDelta(CouponDefinition def, int releaseCents) {
        if (def == null || def.getActivityId() == null || releaseCents <= 0) {
            return;
        }
        promotionService.releaseBudget(def.getActivityId(), releaseCents);
    }

    @Transactional
    public void markUsed(Long userId, Long couponId, String orderId, String deviceId, int discountCents) {
        runWithCouponUseLock(couponId, () -> {
            doMarkUsed(userId, couponId, orderId, deviceId, discountCents);
            return null;
        });
    }

    private void doMarkUsed(Long userId, Long couponId, String orderId, String deviceId, int discountCents) {
        UserCoupon uc = userCouponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券不存在"));
        if (!uc.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该优惠券");
        }
        if (orderId != null && !orderId.isBlank()) {
            var usedOnOrder = userCouponRepository.findByOrderIdAndStatus(orderId, "USED");
            if (usedOnOrder.stream().anyMatch(existing -> existing.getCouponId().equals(couponId))) {
                log.info("coupon already marked used userId={} couponId={} order={}",
                        userId, couponId, orderId);
                return;
            }
            if (!usedOnOrder.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已核销其他优惠券");
            }
        }
        if (!CabinetConstants.COUPON_STATUS_UNUSED.equals(uc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已使用或已过期");
        }
        if (uc.getExpireAt() != null && uc.getExpireAt().isBefore(Instant.now())) {
            uc.setStatus(CabinetConstants.COUPON_STATUS_EXPIRED);
            userCouponRepository.save(uc);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已过期");
        }
        uc.setStatus("USED");
        uc.setUsedAt(Instant.now());
        uc.setOrderId(orderId);
        uc.setDeviceId(deviceId);
        uc.setDiscountCents(Math.max(0, discountCents));
        userCouponRepository.save(uc);
        CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
        reconcilePromotionBudgetOnUse(def, Math.max(0, discountCents));
        log.info("coupon marked used userId={} couponId={} order={} discount={}",
                userId, couponId, orderId, discountCents);
    }

    /** 用券/选券基数：优先明细合计，避免订单头脏折扣字段导致二次打折。 */
    int resolveOrderLineSubtotal(CabinetOrder order) {
        if (order == null || order.getOrderId() == null) {
            return 0;
        }
        int fromLines = orderLineRepository.findByOrderId(order.getOrderId()).stream()
                .mapToInt(CabinetOrderLine::getLineAmountCents)
                .sum();
        if (fromLines > 0) {
            return fromLines;
        }
        return Math.max(0, order.getTotalAmountCents());
    }

    public record BestCoupon(Long couponId, int discountCents, String couponName) {}

    // ── 定时任务：过期处理 ──────────────────────────────

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void expireOverdueCoupons() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(COUPON_EXPIRE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无过期优惠券";
        try {
            List<UserCoupon> expired = userCouponRepository.findByStatusAndExpireAtBefore(CabinetConstants.COUPON_STATUS_UNUSED, Instant.now());
            for (UserCoupon uc : expired) {
                uc.setStatus(CabinetConstants.COUPON_STATUS_EXPIRED);
                CouponDefinition def = definitionRepository.findById(uc.getCouponDefId()).orElse(null);
                releasePromotionBudgetIfAny(def);
            }
            userCouponRepository.saveAll(expired);
            if (!expired.isEmpty()) {
                summary = "过期优惠券 " + expired.size() + " 张";
                log.info("expired {} overdue coupons", expired.size());
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(COUPON_EXPIRE, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(COUPON_EXPIRE, "SUCCESS", summary, start);
            }
        }
    }

    // ── DTO ──────────────────────────────────────────────

    private void validateDefinitionRequest(
            String couponName,
            String couponType,
            int denominationCents,
            int minSpendCents,
            Integer discountPercent,
            int validityDays,
            int maxIssueCount) {
        if (couponName == null || couponName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写优惠券名称");
        }
        if (couponType == null || couponType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择优惠券类型");
        }
        if (validityDays < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有效天数须至少为 1");
        }
        if (maxIssueCount < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "总量限制不能为负数");
        }
        if (minSpendCents < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低消费不能为负数");
        }
        String type = couponType.trim();
        if ("PERCENT_OFF".equals(type)) {
            if (discountPercent == null || discountPercent < 1 || discountPercent > 99) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折扣百分比须在 1～99 之间");
            }
        } else if ("AMOUNT_OFF".equals(type)) {
            if (denominationCents <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "满减券面值须大于 0");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的优惠券类型");
        }
    }

    private void applyDefinitionAmounts(
            CouponDefinition def,
            String couponType,
            int denominationCents,
            int minSpendCents,
            Integer discountPercent) {
        String type = couponType.trim();
        def.setMinSpendCents(Math.max(0, minSpendCents));
        if ("PERCENT_OFF".equals(type)) {
            def.setDenominationCents(0);
            def.setDiscountPercent(discountPercent);
        } else {
            def.setDenominationCents(Math.max(0, denominationCents));
            def.setDiscountPercent(null);
        }
    }

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
        int denomination;
        if (def != null) {
            denomination = def.getDenominationCents();
        } else if (uc.getDiscountCents() != null) {
            denomination = uc.getDiscountCents();
        } else {
            denomination = 0;
        }
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
                uc.getCouponCode(),
                def != null ? def.getDeviceScope() : "ALL",
                def != null ? def.getDescription() : null
        );
    }

    private String generateCouponCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(COUPON_CODE_LENGTH);
        for (int i = 0; i < COUPON_CODE_LENGTH; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    static String couponUseLockKey(Long couponId) {
        return "coupon:use:" + couponId;
    }

    private <T> T runWithCouponUseLock(Long couponId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(couponUseLockKey(couponId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(couponUseLockKey(couponId));
        }
    }
}
