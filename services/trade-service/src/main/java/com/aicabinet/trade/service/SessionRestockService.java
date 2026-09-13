package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 补货会话短事务：快照 begin/complete/fail、任务结束关会话。
 * <p>锁与状态迁移委托 {@link SessionService}；vision/重力 HTTP 仍在锁外由编排方触发。</p>
 */
@Service
public class SessionRestockService {
    private static final String DEVICEID = "deviceId";
    private static final Logger log = LoggerFactory.getLogger(SessionRestockService.class);

    private static final EnumSet<SessionState> RESTOCK_CLOSEABLE_STATES = EnumSet.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.WAITING_UPLOAD, SessionState.RECOGNIZING, SessionState.SETTLING);

    private final ShoppingSessionMapper repository;
    private final RestockSnapshotService restockSnapshotService;
    private final CabinetMetrics cabinetMetrics;
    private final DomainEventPublisher domainEventPublisher;
    private final SessionService sessionService;
    private final SessionRestockService self;

    public SessionRestockService(ShoppingSessionMapper repository,
                                 RestockSnapshotService restockSnapshotService,
                                 CabinetMetrics cabinetMetrics,
                                 DomainEventPublisher domainEventPublisher,
                                 @Lazy SessionService sessionService,
                                 @Lazy SessionRestockService self) {
        this.repository = repository;
        this.restockSnapshotService = restockSnapshotService;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.sessionService = sessionService;
        this.self = self;
    }

    /** 补货关门后：视觉/重力快照回写货道实测，不创建订单。无外层长事务包裹 vision HTTP。 */
    public SessionDto finishRestockSnapshot(String sessionId) {
        return sessionService.runWithSessionLifeLock(sessionId, () -> {
            ShoppingSession ready = self.beginRestockSnapshot(sessionId);
            if (ready == null) {
                return sessionService.toDto(repository.findById(sessionId).orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND)));
            }
            try {
                restockSnapshotService.applySnapshot(ready);
                return self.completeRestockSnapshot(sessionId);
            } catch (RuntimeException e) {
                log.error("restock snapshot failed session={}", sessionId, e);
                return self.failRestockSnapshot(sessionId);
            }
        });
    }

    @Transactional
    public ShoppingSession beginRestockSnapshot(String sessionId) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!DeviceValidationService.isRestockSession(session)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a restock session");
        }
        if (session.getState() != SessionState.RECOGNIZING && session.getState() != SessionState.SHOPPING) {
            return null;
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            sessionService.transition(session, SessionState.SETTLING);
        }
        return session;
    }

    @Transactional
    public SessionDto completeRestockSnapshot(String sessionId) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        sessionService.transition(session, SessionState.COMPLETED);
        log.info("restock snapshot completed session={} device={}", sessionId, session.getDeviceId());
        return sessionService.toDto(session);
    }

    @Transactional
    public SessionDto failRestockSnapshot(String sessionId) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        session.setFailReason("restock snapshot failed");
        sessionService.transition(session, SessionState.FAILED);
        return sessionService.toDto(session);
    }

    /**
     * 补货任务完成/取消时关闭仍占用柜机的补货会话，避免「柜机有未结束会话」挡消费者。
     */
    @Transactional
    public int closeRestockSessionsForTask(Long taskId, String reason) {
        if (taskId == null) {
            return 0;
        }
        String failReason = (reason == null || reason.isBlank()) ? "补货任务结束，自动关闭会话" : reason.trim();
        java.util.LinkedHashMap<String, ShoppingSession> byId = new java.util.LinkedHashMap<>();
        for (ShoppingSession s : repository.findByReplenishmentTaskIdAndStateIn(taskId, RESTOCK_CLOSEABLE_STATES)) {
            byId.put(s.getSessionId(), s);
        }
        for (ShoppingSession s : repository.findByIdempotencyKeyStartingWithAndStateIn(
                "RESTOCK:" + taskId + ":", RESTOCK_CLOSEABLE_STATES)) {
            byId.putIfAbsent(s.getSessionId(), s);
        }
        List<ShoppingSession> open = List.copyOf(byId.values());
        for (ShoppingSession session : open) {
            session.setFailReason(failReason);
            if (session.getCloseTime() == null) {
                session.setCloseTime(Instant.now());
            }
            try {
                restockSnapshotService.applySnapshot(session);
            } catch (Exception e) {
                log.warn("restock auto-close snapshot failed session={} task={}",
                        session.getSessionId(), taskId, e);
            }
            session.setState(SessionState.COMPLETED);
            repository.save(session);
            cabinetMetrics.recordSessionState(SessionState.COMPLETED);
            domainEventPublisher.publish("RestockSessionAutoClosed", session.getSessionId(),
                    Map.of(DEVICEID, session.getDeviceId(), "taskId", String.valueOf(taskId),
                            "reason", failReason));
            log.info("restock session auto-closed session={} task={} reason={}",
                    session.getSessionId(), taskId, failReason);
        }
        return open.size();
    }
}
