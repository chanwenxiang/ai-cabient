package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 门事件与关门路径：开门/关门状态落库，再按会话类型编排补货快照或结算。
 * <p>状态迁移、DTO、会话锁与结算委托 {@link SessionService}，避免行为漂移。</p>
 */
@Service
public class SessionDoorService {
    private static final String DEVICEID = "deviceId";
    private static final Logger log = LoggerFactory.getLogger(SessionDoorService.class);

    private final ShoppingSessionMapper repository;
    private final GravitySettlementHelper gravityHelper;
    private final RestockSnapshotService restockSnapshotService;
    private final SessionRestockService sessionRestockService;
    private final CabinetMetrics cabinetMetrics;
    private final DomainEventPublisher domainEventPublisher;
    private final SessionService sessionService;
    private final SessionDoorService self;
    private final com.aicabinet.trade.service.JiangyiDeviceDirectory jiangyiDeviceDirectory;

    public SessionDoorService(ShoppingSessionMapper repository,
                              GravitySettlementHelper gravityHelper,
                              RestockSnapshotService restockSnapshotService,
                              SessionRestockService sessionRestockService,
                              CabinetMetrics cabinetMetrics,
                              DomainEventPublisher domainEventPublisher,
                              @Lazy SessionService sessionService,
                              @Lazy SessionDoorService self,
                              com.aicabinet.trade.service.JiangyiDeviceDirectory jiangyiDeviceDirectory) {
        this.repository = repository;
        this.gravityHelper = gravityHelper;
        this.restockSnapshotService = restockSnapshotService;
        this.sessionRestockService = sessionRestockService;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.sessionService = sessionService;
        this.self = self;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
    }

    /**
     * 门事件入口。关门状态先单独提交，再结算，避免 vision/扣款失败把「已关门」回滚掉，
     * 导致会话卡在 SHOPPING、设备无法再次开门。
     */
    public SessionDto handleDoorEvent(DoorEventRequest event) {
        SessionDto afterDoor = self.applyDoorEvent(event);
        if (event.doorState() == DoorState.CLOSED && afterDoor.state() != SessionState.WAITING_UPLOAD) {
            ShoppingSession session = repository.findById(event.sessionId()).orElse(null);
            if (session != null && DeviceValidationService.isOpsRemoteSession(session)) {
                return afterDoor;
            }
            // 将邑（CB-022 模式一）：gateway 在识别上报到达时先合成 CLOSED 推进到 RECOGNIZING，
            // 随后立刻转发 edge-results 结算。若此处走 settleAfterClose 会「空车结算」——
            // 识别数据尚未入会话即完成结案，真正的 edge-results 到达时已终态被拒（ALREADY_HANDLED，
            // 2026-10-09 联调实测）。将邑会话关门后停在 RECOGNIZING 等识别结果，与 ops-remote 同豁免模式。
            if (session != null && jiangyiDeviceDirectory.isJiangyi(session.getDeviceId())) {
                return afterDoor;
            }
            if (session != null && DeviceValidationService.isRestockSession(session)) {
                if (afterDoor.state() == SessionState.RECOGNIZING) {
                    return sessionRestockService.finishRestockSnapshot(event.sessionId());
                }
                return afterDoor;
            }
            return sessionService.settleAfterClose(event.sessionId());
        }
        return afterDoor;
    }

    @Transactional
    public SessionDto applyDoorEvent(DoorEventRequest event) {
        return sessionService.runWithSessionLifeLock(event.sessionId(), () -> doApplyDoorEvent(event));
    }

    private SessionDto doApplyDoorEvent(DoorEventRequest event) {
        ShoppingSession session = repository.findByIdForUpdate(event.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));

        if (!session.getDeviceId().equals(event.deviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
        }

        if (event.gravityDeltasJson() != null && !event.gravityDeltasJson().isBlank()) {
            session.setGravityDeltas(gravityHelper.mergeGravityJson(session.getGravityDeltas(), event.gravityDeltasJson()));
        }
        if (event.gravityDeltasJson() != null && !event.gravityDeltasJson().isBlank()) {
            repository.save(session);
        }

        return switch (event.doorState()) {
            case OPEN -> onDoorOpened(session);
            case CLOSED -> onDoorClosed(session);
            default -> sessionService.toDto(session);
        };
    }

    private SessionDto onDoorOpened(ShoppingSession session) {
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            sessionService.transition(session, SessionState.SHOPPING);
            cabinetMetrics.recordDoorOpen(true);
            domainEventPublisher.publish("DoorOpened", session.getSessionId(),
                    Map.of(DEVICEID, session.getDeviceId(), "userId", session.getUserId()));
            log.info("door opened {}", SessionLogContext.of(session));
        }
        return sessionService.toDto(session);
    }

    private SessionDto onDoorClosed(ShoppingSession session) {
        boolean orphanClose = false;
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            sessionService.transition(session, SessionState.SHOPPING);
            orphanClose = true;
            log.warn("door closed while opening, treat as shopping {}", SessionLogContext.of(session));
        }
        if (session.getState() != SessionState.SHOPPING) {
            return sessionService.toDto(session);
        }
        // 关门完整率的分子：只在「从 SHOPPING 正常走到这里」时计一次
        // （重复关门/非 SHOPPING 已在上方 return ⇒ 不重复计数）。
        cabinetMetrics.recordDoorClose(orphanClose);
        session.setCloseTime(Instant.now());
        repository.save(session);

        if (DeviceValidationService.isOpsRemoteSession(session)) {
            // 运维开门：关门即完成，不识别、不结算；有录像则保留供审计
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("ops remote door closed {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        if (DeviceValidationService.isRestockSession(session)) {
            restockSnapshotService.applySnapshot(session);
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("restock door closed with gravity snapshot {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        sessionService.transition(session, SessionState.RECOGNIZING);
        log.info("door closed, recognizing {}", SessionLogContext.of(session));
        return sessionService.toDto(session);
    }
}
