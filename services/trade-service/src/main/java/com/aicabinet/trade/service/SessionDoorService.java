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

    public SessionDoorService(ShoppingSessionMapper repository,
                              GravitySettlementHelper gravityHelper,
                              RestockSnapshotService restockSnapshotService,
                              SessionRestockService sessionRestockService,
                              CabinetMetrics cabinetMetrics,
                              DomainEventPublisher domainEventPublisher,
                              @Lazy SessionService sessionService,
                              @Lazy SessionDoorService self) {
        this.repository = repository;
        this.gravityHelper = gravityHelper;
        this.restockSnapshotService = restockSnapshotService;
        this.sessionRestockService = sessionRestockService;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.sessionService = sessionService;
        this.self = self;
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

        if (event.videoUri() != null && !event.videoUri().isBlank()) {
            session.setVideoUri(event.videoUri());
        }
        applyVideoMetadata(session, event.uploadStatus(), event.videoClipsJson(), event.cameraFusionMode());
        if (event.gravityDeltasJson() != null && !event.gravityDeltasJson().isBlank()) {
            session.setGravityDeltas(gravityHelper.mergeGravityJson(session.getGravityDeltas(), event.gravityDeltasJson()));
        }
        if (event.videoUri() != null || event.uploadStatus() != null || event.videoClipsJson() != null
                || event.gravityDeltasJson() != null) {
            repository.save(session);
        }

        return switch (event.doorState()) {
            case OPEN -> onDoorOpened(session);
            case CLOSED -> onDoorClosed(session, event.videoUri());
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

    private SessionDto onDoorClosed(ShoppingSession session, String videoUri) {
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            sessionService.transition(session, SessionState.SHOPPING);
            log.warn("door closed while opening, treat as shopping {}", SessionLogContext.of(session));
        }
        if (session.getState() != SessionState.SHOPPING) {
            return sessionService.toDto(session);
        }
        session.setCloseTime(Instant.now());
        if (videoUri != null && !videoUri.isBlank()) {
            session.setVideoUri(videoUri);
        }
        repository.save(session);

        if (DeviceValidationService.isOpsRemoteSession(session)) {
            // 运维开门：关门即完成，不识别、不结算；有录像则保留供审计
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("ops remote door closed {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        if (DeviceValidationService.isRestockSession(session)) {
            if (isWaitingForUpload(session)) {
                sessionService.transition(session, SessionState.WAITING_UPLOAD);
                log.info("restock door closed, waiting upload {}", SessionLogContext.of(session));
                return sessionService.toDto(session);
            }
            boolean hasVideo = session.getVideoUri() != null && !session.getVideoUri().isBlank();
            boolean hasSlotGravity = gravityHelper.hasSlotSpecificDeltas(
                    gravityHelper.parse(session.getGravityDeltas()));
            if (hasVideo && !hasSlotGravity) {
                sessionService.transition(session, SessionState.RECOGNIZING);
                log.info("restock door closed, recognizing for snapshot {}", SessionLogContext.of(session));
                return sessionService.toDto(session);
            }
            restockSnapshotService.applySnapshot(session);
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("restock door closed with gravity snapshot {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        if (isWaitingForUpload(session)) {
            sessionService.transition(session, SessionState.WAITING_UPLOAD);
            log.info("door closed, waiting upload {} uploadStatus={}",
                    SessionLogContext.of(session), session.getUploadStatus());
            return sessionService.toDto(session);
        }

        sessionService.transition(session, SessionState.RECOGNIZING);
        log.info("door closed, recognizing {} video={}", SessionLogContext.of(session), session.getVideoUri());
        return sessionService.toDto(session);
    }

    private static boolean isWaitingForUpload(ShoppingSession session) {
        String status = session.getUploadStatus();
        return "LOCAL_QUEUED".equalsIgnoreCase(status) || "UPLOADING".equalsIgnoreCase(status);
    }

    private static void applyVideoMetadata(ShoppingSession session, String uploadStatus,
                                           String videoClipsJson, String cameraFusionMode) {
        if (uploadStatus != null && !uploadStatus.isBlank()) {
            session.setUploadStatus(uploadStatus);
        }
        if (videoClipsJson != null && !videoClipsJson.isBlank()) {
            session.setVideoClips(videoClipsJson);
            if (cameraFusionMode == null || cameraFusionMode.isBlank()) {
                session.setCameraFusionMode("MULTI");
            }
        }
        if (cameraFusionMode != null && !cameraFusionMode.isBlank()) {
            session.setCameraFusionMode(cameraFusionMode);
        }
    }
}
