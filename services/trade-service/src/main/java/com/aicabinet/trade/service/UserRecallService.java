package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UserRecallResult;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 沉睡用户召回：定向发券 + 通知触达，形成「分析→触达→召回」闭环。 */
@Service
public class UserRecallService {

    private static final Logger log = LoggerFactory.getLogger(UserRecallService.class);
    private static final int MAX_RECALL_USERS = 1000;

    private final UserBehaviorAnalyticsService behaviorAnalyticsService;
    private final CouponService couponService;
    private final CouponDefinitionMapper couponDefinitionRepository;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;

    public UserRecallService(UserBehaviorAnalyticsService behaviorAnalyticsService,
                             CouponService couponService,
                             CouponDefinitionMapper couponDefinitionRepository,
                             NotificationService notificationService,
                             DistributedLockService distributedLockService) {
        this.behaviorAnalyticsService = behaviorAnalyticsService;
        this.couponService = couponService;
        this.couponDefinitionRepository = couponDefinitionRepository;
        this.notificationService = notificationService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public UserRecallResult recall(Long couponDefId, Integer days, List<Long> userIds) {
        if (couponDefId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择召回优惠券");
        }
        return runWithRecallCouponLock(couponDefId, () -> doRecall(couponDefId, days, userIds));
    }

    private UserRecallResult doRecall(Long couponDefId, Integer days, List<Long> userIds) {
        CouponDefinition def = couponDefinitionRepository.findByIdForUpdate(couponDefId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠券不存在"));
        if (!"ACTIVE".equalsIgnoreCase(def.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该优惠券已停用");
        }

        List<Long> targets;
        if (userIds != null && !userIds.isEmpty()) {
            targets = userIds.stream().distinct().toList();
        } else {
            int window = days != null ? days : 30;
            targets = behaviorAnalyticsService.summary(window).dormantUsers().stream()
                    .map(r -> r.userId())
                    .distinct()
                    .toList();
        }
        if (targets.isEmpty()) {
            return new UserRecallResult(0, 0);
        }
        if (targets.size() > MAX_RECALL_USERS) {
            targets = targets.subList(0, MAX_RECALL_USERS);
        }

        String couponName = def.getCouponName() == null ? "优惠券" : def.getCouponName();
        couponService.batchIssue(couponDefId, targets);

        int notified = 0;
        for (Long userId : targets) {
            try {
                notificationService.notifyConsumer(
                        userId,
                        "user_recall",
                        Map.of("couponName", couponName),
                        "RECALL",
                        null);
                notified++;
            } catch (Exception e) {
                log.warn("user recall notification failed userId={}", userId, e);
            }
        }
        log.info("user recall issued coupon={} users={} notified={}", couponDefId, targets.size(), notified);
        return new UserRecallResult(targets.size(), notified);
    }

    static String recallCouponLockKey(long couponDefId) {
        return "user-recall:coupon:" + couponDefId;
    }

    private <T> T runWithRecallCouponLock(long couponDefId, Supplier<T> action) {
        String lockKey = recallCouponLockKey(couponDefId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "召回处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }
}
