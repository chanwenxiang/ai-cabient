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
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final PromotionService self;

    public PromotionService(PromotionActivityMapper repository,
                            DistributedLockService distributedLockService, @Lazy PromotionService self) {
        this.repository = repository;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public PromotionActivityDto create(CreatePromotionRequest request) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityName(request.activityName());
        a.setActivityType(request.activityType());
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(request.budgetCents());
        a.setUserLimit(request.userLimit());
        a.setDeviceScope(request.deviceScope());
        a.setRuleConfig(request.ruleConfig() != null ? request.ruleConfig() : "{}");
        a.setDescription(request.description());
        a.setStatus("DRAFT");
        repository.save(a);
        log.info("promotion created id={} name={}", a.getActivityId(), a.getActivityName());
        return toDto(a);
    }

    public List<PromotionActivityDto> listActive() {
        return repository.findByStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE).stream().map(this::toDto).toList();
    }

    public List<PromotionActivityDto> listAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public List<PromotionActivityDto> listCurrentlyRunning() {
        Instant now = Instant.now();
        return repository.findByStatusAndStartTimeBeforeAndEndTimeAfter(CabinetConstants.PROMOTION_STATUS_ACTIVE, now, now)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public PromotionActivityDto updateStatus(Long activityId, String status) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        a.setStatus(status);
        repository.save(a);
        log.info("promotion {} status={}", activityId, status);
        return toDto(a);
    }

    @Transactional
    public PromotionActivityDto update(Long activityId, CreatePromotionRequest request) {
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
        a.setActivityType(request.activityType());
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(request.budgetCents());
        a.setUserLimit(request.userLimit());
        if (request.deviceScope() != null) {
            a.setDeviceScope(request.deviceScope());
        }
        if (request.ruleConfig() != null) {
            a.setRuleConfig(request.ruleConfig());
        }
        a.setDescription(request.description());
        repository.save(a);
        log.info("promotion updated id={}", activityId);
        return toDto(a);
    }

    @Transactional
    public PromotionActivityDto launch(Long activityId) {
        return self.updateStatus(activityId, CabinetConstants.PROMOTION_STATUS_ACTIVE);
    }

    @Transactional
    public PromotionActivityDto stop(Long activityId) {
        return self.updateStatus(activityId, "STOPPED");
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

    /** 领券预算占用额：默认按券面额。 */
    public static int budgetReserveCents(CouponDefinition def) {
        if (def == null) {
            return 0;
        }
        return Math.max(0, def.getDenominationCents());
    }

    private PromotionActivityDto toDto(PromotionActivity a) {
        return new PromotionActivityDto(
                a.getActivityId(), a.getActivityName(), a.getActivityType(),
                a.getStatus(), a.getStartTime(), a.getEndTime(),
                a.getBudgetCents(), a.getUsedCents(), a.getUserLimit(),
                a.getDeviceScope(), a.getRuleConfig(), a.getDescription());
    }
}
