package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.SessionExpireProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 会话超时调度：开门未响应、补货/消费者购物滞留、识别结算超时升级。
 * <p>状态迁移与领域事件发布委托 {@link SessionService#transition}，避免行为漂移。</p>
 */
@Service
public class SessionExpireService {
    private static final String SESSION_RECOGNIZING_EXPIRE = "session-recognizing-expire";
    private static final String SESSION_OPENING_EXPIRE = "session-opening-expire";
    private static final String SESSION_RESTOCK_EXPIRE = "session-restock-expire";
    private static final String SESSION_DOOR_OPEN_EXPIRE = "session-door-open-expire";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private static final Logger log = LoggerFactory.getLogger(SessionExpireService.class);

    private final ShoppingSessionMapper repository;
    private final SessionExpireProperties sessionExpireProperties;
    private final CabinetMetrics cabinetMetrics;
    private final RestockSnapshotService restockSnapshotService;
    private final OpsExceptionService opsExceptionService;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DistributedLockService distributedLockService;
    private final ScheduledTaskService taskService;
    private final SessionService sessionService;

    public SessionExpireService(ShoppingSessionMapper repository,
                                SessionExpireProperties sessionExpireProperties,
                                CabinetMetrics cabinetMetrics,
                                RestockSnapshotService restockSnapshotService,
                                OpsExceptionService opsExceptionService,
                                @Lazy DisputeService disputeService,
                                ConsumerPreauthService consumerPreauthService,
                                DistributedLockService distributedLockService,
                                ScheduledTaskService taskService,
                                @Lazy SessionService sessionService) {
        this.repository = repository;
        this.sessionExpireProperties = sessionExpireProperties != null
                ? sessionExpireProperties
                : SessionExpireProperties.defaults();
        this.cabinetMetrics = cabinetMetrics;
        this.restockSnapshotService = restockSnapshotService;
        this.opsExceptionService = opsExceptionService;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
        this.distributedLockService = distributedLockService;
        this.taskService = taskService;
        this.sessionService = sessionService;
    }

    /** 开门指令已下发但设备未响应时，自动释放会话与设备占用。 */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void expireStaleOpeningSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(SESSION_OPENING_EXPIRE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无超时开门会话";
        try {
            Instant cutoff = Instant.now().minus(sessionExpireProperties.openingSeconds(), ChronoUnit.SECONDS);
            var stale = repository.findByStateInAndCreatedAtBefore(
                    List.of(SessionState.OPENING, SessionState.CREATED), cutoff, 500);
            int cancelled = 0;
            for (ShoppingSession s : stale) {
                if (expireOneStaleOpeningSession(s.getSessionId(), cutoff)) {
                    cancelled++;
                }
            }
            if (cancelled > 0) {
                summary = "取消超时开门会话 " + cancelled + " 个";
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(SESSION_OPENING_EXPIRE, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(SESSION_OPENING_EXPIRE, STATUS_SUCCESS, summary, start);
            }
        }
    }

    /** 补货会话长时间停在购物态：超时清理，避免挡消费者开门。 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleRestockShoppingSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(SESSION_RESTOCK_EXPIRE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无超时补货会话";
        try {
            Instant cutoff = Instant.now().minus(sessionExpireProperties.restockShoppingMinutes(), ChronoUnit.MINUTES);
            var stale = repository.findByStateInAndUpdatedAtBefore(
                            List.of(SessionState.SHOPPING, SessionState.WAITING_UPLOAD), cutoff, 500)
                    .stream()
                    .filter(DeviceValidationService::isRestockSession)
                    .toList();
            int closed = 0;
            for (ShoppingSession s : stale) {
                if (expireOneStaleRestockSession(s.getSessionId(), cutoff)) {
                    closed++;
                }
            }
            if (closed > 0) {
                summary = "关闭超时补货会话 " + closed + " 个";
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(SESSION_RESTOCK_EXPIRE, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(SESSION_RESTOCK_EXPIRE, STATUS_SUCCESS, summary, start);
            }
        }
    }

    /**
     * 消费者购物态开门超时：自动关会话并释放设备占用（与 DOOR_OPEN_TOO_LONG 告警阈值对齐）。
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleConsumerShoppingSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(SESSION_DOOR_OPEN_EXPIRE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无开门超时购物会话";
        try {
            Instant cutoff = Instant.now().minus(sessionExpireProperties.consumerDoorOpenMinutes(), ChronoUnit.MINUTES);
            int closed = 0;
            for (ShoppingSession s : repository.findByStateAndOpenTimeBefore(
                    SessionState.SHOPPING, cutoff, 500)) {
                if (DeviceValidationService.isNonConsumerSession(s)) {
                    continue;
                }
                if (expireOneStaleConsumerShoppingSession(s.getSessionId(), cutoff)) {
                    closed++;
                }
            }
            if (closed > 0) {
                summary = "关闭开门超时购物会话 " + closed + " 个";
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(SESSION_DOOR_OPEN_EXPIRE, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(SESSION_DOOR_OPEN_EXPIRE, STATUS_SUCCESS, summary, start);
            }
        }
    }

    /** 识别/结算长时间无结果：转争议，避免占柜机与前端一直卡在「识别中」。 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleRecognizingSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(SESSION_RECOGNIZING_EXPIRE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无识别超时会话";
        try {
            Instant cutoff = Instant.now().minus(sessionExpireProperties.recognizingMinutes(), ChronoUnit.MINUTES);
            int upgraded = 0;
            for (ShoppingSession s : repository.findByStateInAndUpdatedAtBefore(
                    List.of(SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING),
                    cutoff, 500)) {
                if (upgradeStaleRecognizingSession(s, cutoff)) {
                    upgraded++;
                }
            }
            if (upgraded > 0) {
                summary = "识别超时升级 " + upgraded + " 个会话";
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(SESSION_RECOGNIZING_EXPIRE, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(SESSION_RECOGNIZING_EXPIRE, STATUS_SUCCESS, summary, start);
            }
        }
    }

    private boolean upgradeStaleRecognizingSession(ShoppingSession session, Instant cutoff) {
        Instant anchor;
        if (session.getCloseTime() != null) {
            anchor = session.getCloseTime();
        } else if (session.getUpdatedAt() != null) {
            anchor = session.getUpdatedAt();
        } else {
            anchor = session.getCreatedAt();
        }
        if (anchor == null || !anchor.isBefore(cutoff)) {
            return false;
        }
        try {
            if (DeviceValidationService.isRestockSession(session)) {
                closeStaleRestockRecognizing(session);
                return true;
            }
            upgradeConsumerRecognizingTimeout(session);
            return true;
        } catch (Exception e) {
            log.warn("识别超时升级失败 {}", SessionLogContext.of(session), e);
            return false;
        }
    }

    private void upgradeConsumerRecognizingTimeout(ShoppingSession session) {
        SessionState from = session.getState();
        String failReason = "识别超时，已转人工审核，本次暂未扣款";
        if (from.canTransitionTo(SessionState.DISPUTED)) {
            session.setFailReason(failReason);
            sessionService.transition(session, SessionState.DISPUTED);
            try {
                disputeService.createTimeoutTicket(session, failReason);
            } catch (Exception ticketEx) {
                log.warn("超时争议单创建失败 {}", SessionLogContext.of(session), ticketEx);
            }
        } else if (from.canTransitionTo(SessionState.FAILED)) {
            session.setFailReason("识别超时");
            sessionService.transition(session, SessionState.FAILED);
        } else {
            session.setFailReason("识别超时");
            sessionService.transition(session, SessionState.FAILED);
        }
        opsExceptionService.report("RECOGNITION_TIMEOUT", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "识别超时", "关门后超过10分钟未完成识别结算");
        log.warn("识别超时会话已升级 {} from={} to={}",
                SessionLogContext.of(session), from, session.getState());
    }

    private void closeStaleRestockRecognizing(ShoppingSession session) {
        session.setFailReason("补货识别超时自动关闭");
        if (session.getCloseTime() == null) {
            session.setCloseTime(Instant.now());
        }
        try {
            restockSnapshotService.applySnapshot(session);
        } catch (Exception e) {
            log.warn("补货超时快照失败 {}，仍关闭会话", SessionLogContext.of(session), e);
        }
        sessionService.transition(session, SessionState.COMPLETED);
        opsExceptionService.report("RESTOCK_RECOGNITION_TIMEOUT", "MEDIUM", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "补货识别超时", "补货关门后超过10分钟未完成货道快照");
        log.warn("restock recognizing session expired {}", SessionLogContext.of(session));
    }

    /**
     * 超时开门会话：加会话锁 + 行锁后再取消，避免与并发结算/关门竞态误杀。
     * 抢锁失败则跳过本轮，下一趟调度再试。
     */
    private boolean expireOneStaleOpeningSession(String sessionId, Instant cutoff) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.debug("expire opening skipped busy session={}", sessionId);
            return false;
        }
        try {
            ShoppingSession locked = repository.findByIdForUpdate(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            if (locked.getState() != SessionState.OPENING && locked.getState() != SessionState.CREATED) {
                return false;
            }
            if (locked.getCreatedAt() != null && locked.getCreatedAt().isAfter(cutoff)) {
                return false;
            }
            consumerPreauthService.releaseIfFrozen(locked);
            sessionService.transition(locked, SessionState.CANCELLED);
            opsExceptionService.report(
                    "OPEN_TIMEOUT",
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            locked.getDeviceId(), locked.getSessionId(), locked.getOrderId(), locked.getUserId()),
                    "开门超时",
                    "开门命令在90秒内未得到设备响应");
            log.warn("opening session expired {}", SessionLogContext.of(locked));
            return true;
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }

    private boolean expireOneStaleRestockSession(String sessionId, Instant cutoff) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.debug("expire restock skipped busy session={}", sessionId);
            return false;
        }
        try {
            ShoppingSession locked = repository.findByIdForUpdate(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            if (locked.getState() != SessionState.SHOPPING && locked.getState() != SessionState.WAITING_UPLOAD) {
                return false;
            }
            if (!DeviceValidationService.isRestockSession(locked)) {
                return false;
            }
            if (locked.getUpdatedAt() != null && locked.getUpdatedAt().isAfter(cutoff)) {
                return false;
            }
            locked.setFailReason("补货会话超时自动关闭");
            if (locked.getCloseTime() == null) {
                locked.setCloseTime(Instant.now());
            }
            sessionService.transition(locked, SessionState.CANCELLED);
            opsExceptionService.report(
                    "RESTOCK_SESSION_TIMEOUT",
                    "MEDIUM",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            locked.getDeviceId(), locked.getSessionId(), locked.getOrderId(), locked.getUserId()),
                    "补货会话超时",
                    "补货开门后超过" + sessionExpireProperties.restockShoppingMinutes() + "分钟未结束");
            log.warn("restock shopping session expired {}", SessionLogContext.of(locked));
            return true;
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }

    /**
     * 消费者购物开门超时：加会话锁 + 行锁后取消，释放预授权与设备占用。
     */
    private boolean expireOneStaleConsumerShoppingSession(String sessionId, Instant cutoff) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.debug("expire consumer shopping skipped busy session={}", sessionId);
            return false;
        }
        try {
            ShoppingSession locked = repository.findByIdForUpdate(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            if (locked.getState() != SessionState.SHOPPING) {
                return false;
            }
            if (DeviceValidationService.isNonConsumerSession(locked)) {
                return false;
            }
            if (locked.getOpenTime() != null && locked.getOpenTime().isAfter(cutoff)) {
                return false;
            }
            consumerPreauthService.releaseIfFrozen(locked);
            locked.setFailReason("开门超时自动关闭（超过" + sessionExpireProperties.consumerDoorOpenMinutes() + "分钟未关门）");
            if (locked.getCloseTime() == null) {
                locked.setCloseTime(Instant.now());
            }
            sessionService.transition(locked, SessionState.CANCELLED);
            opsExceptionService.report(
                    "DOOR_OPEN_TOO_LONG",
                    "CRITICAL",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            locked.getDeviceId(), locked.getSessionId(), locked.getOrderId(), locked.getUserId()),
                    "柜门长时间未关闭",
                    "柜门开启超过 " + sessionExpireProperties.consumerDoorOpenMinutes() + " 分钟，已自动关闭会话并释放设备");
            opsExceptionService.resolveSystem(
                    "DOOR_OPEN_TOO_LONG",
                    locked.getSessionId(),
                    "开门超时已自动关闭会话并释放设备");
            log.warn("consumer shopping session expired {}", SessionLogContext.of(locked));
            return true;
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }
}
