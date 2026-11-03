package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class PromotionService {
    private static final String LITERAL = "活动不存在";


    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionActivityMapper repository;
    private final DistributedLockService distributedLockService;
    private final PromotionActivityValidator validator;
    /** MA-082：活动上下架/改规则直接决定定价与预算，此前零留痕。 */
    private final AdminAuditService auditService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final PromotionService self;

    public PromotionService(PromotionActivityMapper repository,
                            DistributedLockService distributedLockService,
                            PromotionActivityValidator validator,
                            AdminAuditService auditService,
                            @Lazy PromotionService self) {
        this.repository = repository;
        this.distributedLockService = distributedLockService;
        this.validator = validator;
        this.auditService = auditService;
        this.self = self;
    }

    @Transactional
    public PromotionActivityDto create(Long operatorId, CreatePromotionRequest request) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityName(request.activityName());
        a.setActivityType(normalizedActivityType(request.activityType()));
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(requireNonNegativeBudget(request.budgetCents()));
        a.setUserLimit(request.userLimit());
        a.setDeviceScope(request.deviceScope());
        a.setRuleConfig(request.ruleConfig() != null ? request.ruleConfig() : "{}");
        a.setDescription(request.description());
        a.setReduceCents(request.reduceCents());
        a.setDiscountBps(request.discountBps());
        a.setFixedPriceCents(request.fixedPriceCents());
        a.setStatus("DRAFT");
        validator.validateForSave(a);
        repository.save(a);
        log.info("promotion created id={} name={}", a.getActivityId(), a.getActivityName());
        auditService.appendLog(operatorId, "PROMOTION_CREATE", "PROMOTION", String.valueOf(a.getActivityId()),
                "type=" + a.getActivityType() + ",budgetCents=" + a.getBudgetCents());
        return toDto(a);
    }

    public List<PromotionActivityDto> listActive() {
        return repository.findByStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE).stream().map(this::toDto).toList();
    }

    public List<PromotionActivityDto> listAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public PageResult<PromotionActivityDto> listPage(String q, String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = repository.searchPage(q, status, p, s);
        List<PromotionActivityDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    public List<PromotionActivityDto> listCurrentlyRunning() {
        Instant now = Instant.now();
        return repository.findByStatusAndStartTimeBeforeAndEndTimeAfter(CabinetConstants.PROMOTION_STATUS_ACTIVE, now, now)
                .stream().map(this::toDto).toList();
    }

    /** 券定义绑定活动时校验活动存在。 */
    public void requireExists(Long activityId) {
        if (activityId == null) {
            return;
        }
        repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
    }

    @Transactional
    public PromotionActivityDto updateStatus(Long operatorId, Long activityId, String status) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        a.setStatus(status);
        repository.save(a);
        log.info("promotion {} status={}", activityId, status);
        auditService.appendLog(operatorId, "PROMOTION_STATUS", "PROMOTION", String.valueOf(activityId),
                "status=" + status);
        return toDto(a);
    }

    @Transactional
    public PromotionActivityDto update(Long operatorId, Long activityId, CreatePromotionRequest request) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        if (CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "进行中的活动请先停止再编辑");
        }
        if (request.endTime() != null && request.startTime() != null
                && !request.endTime().isAfter(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时间需晚于开始时间");
        }
        a.setActivityName(request.activityName());
        a.setActivityType(normalizedActivityType(request.activityType()));
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(requireNonNegativeBudget(request.budgetCents()));
        a.setUserLimit(request.userLimit());
        if (request.deviceScope() != null) {
            a.setDeviceScope(request.deviceScope());
        }
        if (request.ruleConfig() != null) {
            a.setRuleConfig(request.ruleConfig());
        }
        a.setDescription(request.description());
        a.setReduceCents(request.reduceCents());
        a.setDiscountBps(request.discountBps());
        a.setFixedPriceCents(request.fixedPriceCents());
        validator.validateForSave(a);
        repository.save(a);
        log.info("promotion updated id={}", activityId);
        auditService.appendLog(operatorId, "PROMOTION_UPDATE", "PROMOTION", String.valueOf(activityId),
                "type=" + a.getActivityType() + ",budgetCents=" + a.getBudgetCents());
        return toDto(a);
    }

    @Transactional
    public PromotionActivityDto launch(Long operatorId, Long activityId) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        validator.validateForLaunch(a);
        return self.updateStatus(operatorId, activityId, CabinetConstants.PROMOTION_STATUS_ACTIVE);
    }

    @Transactional
    public PromotionActivityDto stop(Long operatorId, Long activityId) {
        return self.updateStatus(operatorId, activityId, "STOPPED");
    }

    @Transactional
    public void reserveBudgetOnClaim(Long activityId, int reserveCents) {
        if (activityId == null || reserveCents <= 0) {
            return;
        }
        runWithActivityLock(activityId, () -> doReserveBudgetOnClaim(activityId, reserveCents));
    }

    private void doReserveBudgetOnClaim(Long activityId, int reserveCents) {
        PromotionActivity activity = repository.findByIdForUpdate(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        long budget = activity.getBudgetCents();
        long used = activity.getUsedCents();
        if (budget > 0 && used + reserveCents > budget) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动预算已用完");
        }
        activity.setUsedCents(used + reserveCents);
        activity.setUpdatedAt(Instant.now());
        repository.save(activity);
        log.info("promotion budget reserved activity={} reserve={} used={}/{}", activityId, reserveCents,
                activity.getUsedCents(), budget);
    }

    /** 券退还/作废时释放已占用预算。 */
    @Transactional
    public void releaseBudget(Long activityId, int releaseCents) {
        if (activityId == null || releaseCents <= 0) {
            return;
        }
        runWithActivityLock(activityId, () -> doReleaseBudget(activityId, releaseCents));
    }

    private void doReleaseBudget(Long activityId, int releaseCents) {
        repository.findByIdForUpdate(activityId).ifPresent(activity -> {
            activity.setUsedCents(Math.max(0, activity.getUsedCents() - releaseCents));
            activity.setUpdatedAt(Instant.now());
            repository.save(activity);
            log.info("promotion budget released activity={} release={} used={}", activityId, releaseCents,
                    activity.getUsedCents());
        });
    }

    static String promotionActivityLockKey(Long activityId) {
        return "promotion:activity:" + activityId;
    }

    private void runWithActivityLock(Long activityId, Runnable action) {
        runWithActivityLock(activityId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runWithActivityLock(Long activityId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(promotionActivityLockKey(activityId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(promotionActivityLockKey(activityId));
        }
    }

    /**
     * 领券预算占用额：满减券按面额；折扣券（PERCENT_OFF）无面额，按面额估算预留——
     * 以每 100 元消费为基准，预留 {@code discountPercent × 100} 分
     * （如 discountPercent=15 → 每百元优惠 15 元 → 预留 1500 分）。
     * 实际抵扣随订单金额浮动，核销时按「预留 − 实抵」差额回补（见 CouponService.reconcilePromotionBudgetOnUse），
     * 领券与核销两处统一走本口径。
     */
    public static int budgetReserveCents(CouponDefinition def) {
        if (def == null) {
            return 0;
        }
        if (def.getDenominationCents() > 0) {
            return def.getDenominationCents();
        }
        if ("PERCENT_OFF".equals(def.getCouponType()) && def.getDiscountPercent() != null) {
            // 面额估算口径：rate×100 分 = 每百元消费的预计优惠额
            return Math.max(0, def.getDiscountPercent()) * 100;
        }
        return 0;
    }

    private PromotionActivityDto toDto(PromotionActivity a) {
        return new PromotionActivityDto(
                a.getActivityId(), a.getActivityName(), a.getActivityType(),
                a.getStatus(), a.getStartTime(), a.getEndTime(),
                a.getBudgetCents(), a.getUsedCents(), a.getUserLimit(),
                a.getDeviceScope(), a.getRuleConfig(), a.getDescription(),
                a.getReduceCents(), a.getDiscountBps(), a.getFixedPriceCents());
    }
    /**
     * MA-085：活动类型必填入库前先归一大写。判定侧多处用 equalsIgnoreCase 看着没事，
     * 但 {@code PromotionActivityMapper:30} 的定价筛选是 {@code activity_type IN ('SKU_REDUCE',...)}——
     * 存成小写就静默不参与活动定价面。
     */
    private static String normalizedActivityType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activityType 必填");
        }
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * MA-085：预算非负（0=不限额是既定语义）。负数会让 reserveBudget 的
     * {@code budget > 0} 闸整体跳过，used_cents 变成无上限推进 ⇒ 活动预算这的资金闸形同虚设。
     */
    private static long requireNonNegativeBudget(long budgetCents) {
        if (budgetCents < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "budgetCents 不得为负（0 = 不限额）");
        }
        return budgetCents;
    }
}
