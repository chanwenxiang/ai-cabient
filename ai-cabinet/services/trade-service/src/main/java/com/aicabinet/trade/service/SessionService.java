package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.SessionCartRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.VideoAttachRequest;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final long OPENING_EXPIRE_SECONDS = 90;

    private final ShoppingSessionRepository repository;
    private final DeviceServiceClient deviceClient;
    private final UserValidationService userValidationService;
    private final DeviceValidationService deviceValidationService;
    private final SettlementService settlementService;
    private final VisionAsyncProperties visionAsyncProperties;
    private final CabinetMetrics cabinetMetrics;
    private final DomainEventPublisher domainEventPublisher;
    private final GravitySettlementHelper gravityHelper;
    private final RestockSnapshotService restockSnapshotService;
    private final SessionService self;

    public SessionService(ShoppingSessionRepository repository,
                          DeviceServiceClient deviceClient,
                          UserValidationService userValidationService,
                          DeviceValidationService deviceValidationService,
                          SettlementService settlementService,
                          VisionAsyncProperties visionAsyncProperties,
                          CabinetMetrics cabinetMetrics,
                          DomainEventPublisher domainEventPublisher,
                          GravitySettlementHelper gravityHelper,
                          RestockSnapshotService restockSnapshotService,
                          @Lazy SessionService self) {
        this.repository = repository;
        this.deviceClient = deviceClient;
        this.userValidationService = userValidationService;
        this.deviceValidationService = deviceValidationService;
        this.settlementService = settlementService;
        this.visionAsyncProperties = visionAsyncProperties;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.gravityHelper = gravityHelper;
        this.restockSnapshotService = restockSnapshotService;
        this.self = self;
    }

    @Transactional
    public SessionDto createSession(Long userId, CreateSessionRequest request) {
        if (request.idempotencyKey() != null) {
            return repository.findByIdempotencyKey(request.idempotencyKey())
                    .map(this::toDto)
                    .orElseGet(() -> doCreateSession(userId, request));
        }
        return doCreateSession(userId, request);
    }

    private SessionDto doCreateSession(Long userId, CreateSessionRequest request) {
        userValidationService.validateCanOpenDoor(userId, request.deviceId());
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        session.setState(SessionState.CREATED);
        session.setIdempotencyKey(request.idempotencyKey());
        repository.save(session);

        transition(session, SessionState.OPENING);
        deviceClient.requestOpenDoor(session.getSessionId(), session.getDeviceId(), userId, false);

        return toDto(session);
    }

    /**
     * 开发本地识别测试：创建会话并直接进入 SHOPPING，不下发 MQTT 开门指令。
     */
    @Transactional
    public SessionDto createSessionForDevTest(Long userId, CreateSessionRequest request) {
        userValidationService.validateCanOpenDoor(userId, request.deviceId());
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        session.setState(SessionState.CREATED);
        repository.save(session);

        transition(session, SessionState.OPENING);
        session.setOpenTime(Instant.now());
        transition(session, SessionState.SHOPPING);
        log.info("dev test session shopping session={} device={}", session.getSessionId(), request.deviceId());
        return toDto(session);
    }

    /** 开发测试：将已有会话推进到 SHOPPING，便于模拟关门识别。 */
    @Transactional
    public SessionDto ensureShoppingForDevTest(String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() == SessionState.CREATED) {
            transition(session, SessionState.OPENING);
        }
        if (session.getState() == SessionState.OPENING) {
            if (session.getOpenTime() == null) {
                session.setOpenTime(Instant.now());
            }
            transition(session, SessionState.SHOPPING);
        }
        if (session.getState() != SessionState.SHOPPING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.SESSION_STATE_INVALID + "（需 CREATED/OPENING/SHOPPING）");
        }
        return toDto(session);
    }

    /** 开发上传识别：用真实 vision 结果结算，不走 mock 兜底。 */
    @Transactional
    public SessionDto completeDevUploadRecognition(String sessionId,
                                                   VisionServiceClient.RecognitionResult recognition) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() == SessionState.SHOPPING) {
            transition(session, SessionState.RECOGNIZING);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            transition(session, SessionState.SETTLING);
        }
        try {
            OrderDto order = settlementService.processRecognitionResult(session, recognition, false);
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            log.info("dev upload session completed session={} order={}", sessionId, order.orderId());
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            log.warn("dev upload session disputed session={}", sessionId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                transition(session, SessionState.DISPUTED);
                log.warn("session disputed session={} reason={}", session.getSessionId(), e.getReason());
                return toDto(session);
            }
            session.setFailReason(e.getReason());
            transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("dev upload session failed session={} reason={}", sessionId, e.getReason());
        } catch (RuntimeException e) {
            session.setFailReason(ApiMessages.INTERNAL_ERROR);
            if (session.getState().canTransitionTo(SessionState.FAILED)) {
                transition(session, SessionState.FAILED);
            }
            repository.save(session);
            log.error("dev upload settle failed session={}", sessionId, e);
        }
        return toDto(session);
    }

    /**
     * 门事件入口。关门状态先单独提交，再结算，避免 vision/扣款失败把「已关门」回滚掉，
     * 导致会话卡在 SHOPPING、设备无法再次开门。
     */
    public SessionDto handleDoorEvent(DoorEventRequest event) {
        SessionDto afterDoor = self.applyDoorEvent(event);
        if (event.doorState() == DoorState.CLOSED && afterDoor.state() != SessionState.WAITING_UPLOAD) {
            ShoppingSession session = repository.findById(event.sessionId()).orElse(null);
            if (session != null && isRestockSession(session)) {
                if (afterDoor.state() == SessionState.RECOGNIZING) {
                    return self.finishRestockSnapshot(event.sessionId());
                }
                return afterDoor;
            }
            return self.settleAfterClose(event.sessionId());
        }
        return afterDoor;
    }

    @Transactional
    public SessionDto applyDoorEvent(DoorEventRequest event) {
        ShoppingSession session = repository.findById(event.sessionId())
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
            default -> toDto(session);
        };
    }

    @Transactional
    public SessionDto attachVideo(VideoAttachRequest request) {
        ShoppingSession session = repository.findById(request.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!session.getDeviceId().equals(request.deviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
        }
        session.setVideoUri(request.videoUri());
        applyVideoMetadata(session, request.uploadStatus(), request.videoClipsJson(), request.cameraFusionMode());
        repository.save(session);

        if (session.getState() == SessionState.WAITING_UPLOAD) {
            transition(session, SessionState.RECOGNIZING);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            if (isRestockSession(session)) {
                return finishRestockSnapshot(session.getSessionId());
            }
            return settleSession(session);
        }
        return toDto(session);
    }

    /** 补货关门后：视觉/重力快照回写货道实测，不创建订单。 */
    @Transactional
    public SessionDto finishRestockSnapshot(String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!isRestockSession(session)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a restock session");
        }
        if (session.getState() != SessionState.RECOGNIZING && session.getState() != SessionState.SHOPPING) {
            return toDto(session);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            transition(session, SessionState.SETTLING);
        }
        try {
            restockSnapshotService.applySnapshot(session);
            transition(session, SessionState.COMPLETED);
            log.info("restock snapshot completed session={} device={}", sessionId, session.getDeviceId());
        } catch (RuntimeException e) {
            log.error("restock snapshot failed session={}", sessionId, e);
            session.setFailReason("restock snapshot failed");
            transition(session, SessionState.FAILED);
        }
        return toDto(session);
    }

    @Transactional
    public SessionDto attachGravityDeltas(GravityDeltaRequest request) {
        ShoppingSession session = repository.findById(request.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!session.getDeviceId().equals(request.deviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
        }
        String merged = gravityHelper.fromRequestItems(request.deltas());
        session.setGravityDeltas(gravityHelper.mergeGravityJson(session.getGravityDeltas(), merged));
        repository.save(session);
        log.info("gravity deltas attached session={} device={}", session.getSessionId(), session.getDeviceId());
        return toDto(session);
    }

    /** 演示/开发：消费者点选商品同步到会话，关门 mock 结算时按此列表扣款。 */
    @Transactional
    public SessionDto updateSessionCart(Long userId, String sessionId, SessionCartRequest request) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        if (!EnumSet.of(SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        List<GravityDeltaRequest.GravityDeltaItem> deltas = (request.items() == null ? List.<SessionCartRequest.CartItem>of() : request.items())
                .stream()
                .filter(item -> item.qty() > 0)
                .map(item -> new GravityDeltaRequest.GravityDeltaItem(item.skuId(), -item.qty(), null))
                .toList();
        session.setGravityDeltas(deltas.isEmpty() ? null : gravityHelper.fromRequestItems(deltas));
        repository.save(session);
        log.info("session cart updated session={} items={}", sessionId, deltas.size());
        return toDto(session);
    }

    private SessionDto onDoorOpened(ShoppingSession session) {
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            transition(session, SessionState.SHOPPING);
            cabinetMetrics.recordDoorOpen(true);
            domainEventPublisher.publish("DoorOpened", session.getSessionId(),
                    Map.of("deviceId", session.getDeviceId(), "userId", session.getUserId()));
            log.info("door opened session={}", session.getSessionId());
        }
        return toDto(session);
    }

    private SessionDto onDoorClosed(ShoppingSession session, String videoUri) {
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            transition(session, SessionState.SHOPPING);
            log.warn("door closed while opening, treat as shopping session={}", session.getSessionId());
        }
        if (session.getState() != SessionState.SHOPPING) {
            return toDto(session);
        }
        session.setCloseTime(Instant.now());
        if (videoUri != null && !videoUri.isBlank()) {
            session.setVideoUri(videoUri);
        }
        repository.save(session);

        if (isRestockSession(session)) {
            if (isWaitingForUpload(session)) {
                transition(session, SessionState.WAITING_UPLOAD);
                log.info("restock door closed, waiting upload session={}", session.getSessionId());
                return toDto(session);
            }
            boolean hasVideo = session.getVideoUri() != null && !session.getVideoUri().isBlank();
            boolean hasSlotGravity = gravityHelper.hasSlotSpecificDeltas(
                    gravityHelper.parse(session.getGravityDeltas()));
            if (hasVideo && !hasSlotGravity) {
                transition(session, SessionState.RECOGNIZING);
                log.info("restock door closed, recognizing for snapshot session={}", session.getSessionId());
                return toDto(session);
            }
            restockSnapshotService.applySnapshot(session);
            transition(session, SessionState.COMPLETED);
            log.info("restock door closed with gravity snapshot session={}", session.getSessionId());
            return toDto(session);
        }

        if (isWaitingForUpload(session)) {
            transition(session, SessionState.WAITING_UPLOAD);
            log.info("door closed, waiting upload session={} uploadStatus={}",
                    session.getSessionId(), session.getUploadStatus());
            return toDto(session);
        }

        transition(session, SessionState.RECOGNIZING);
        log.info("door closed, recognizing session={} video={}", session.getSessionId(), session.getVideoUri());
        return toDto(session);
    }

    /** 关门事务提交后再结算，避免 vision 异常回滚门状态。 */
    @Transactional
    public SessionDto settleAfterClose(String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() != SessionState.RECOGNIZING) {
            return toDto(session);
        }
        return settleSession(session);
    }

    private SessionDto settleSession(ShoppingSession session) {
        if (session.getState() == SessionState.COMPLETED) {
            return toDto(session);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            if (visionAsyncProperties.enabled()) {
                settlementService.submitAsyncRecognition(session);
                return toDto(session);
            }
            transition(session, SessionState.SETTLING);
        }
        try {
            OrderDto order = settlementService.settle(session);
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            log.info("session completed session={} order={}", session.getSessionId(), order.orderId());
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            log.warn("session disputed session={}", session.getSessionId());
            return toDto(session);
        } catch (ResponseStatusException e) {
            session.setFailReason(e.getReason());
            transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("session failed session={} reason={}", session.getSessionId(), e.getReason());
            return toDto(session);
        } catch (RestClientException e) {
            log.error("vision/settle remote call failed session={}", session.getSessionId(), e);
            transition(session, SessionState.FAILED);
            return toDto(session);
        } catch (RuntimeException e) {
            log.error("settle failed session={}", session.getSessionId(), e);
            session.setFailReason(ApiMessages.INTERNAL_ERROR);
            if (session.getState().canTransitionTo(SessionState.FAILED)) {
                transition(session, SessionState.FAILED);
            }
            repository.save(session);
            return toDto(session);
        }
        return toDto(session);
    }

    @Transactional
    public void completeAsyncRecognition(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() != SessionState.RECOGNIZING) {
            log.warn("ignore async recognition session={} state={}", sessionId, session.getState());
            return;
        }
        transition(session, SessionState.SETTLING);
        try {
            OrderDto order = settlementService.processRecognitionResult(session, recognition);
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            log.info("async session completed session={} order={}", sessionId, order.orderId());
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            log.warn("async session disputed session={}", sessionId);
            return;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                transition(session, SessionState.DISPUTED);
                log.warn("async session disputed session={}", sessionId);
                return;
            }
            session.setFailReason(e.getReason());
            transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("async session failed session={} reason={}", sessionId, e.getReason());
            return;
        }
    }

    private boolean isWaitingForUpload(ShoppingSession session) {
        String status = session.getUploadStatus();
        return "LOCAL_QUEUED".equalsIgnoreCase(status) || "UPLOADING".equalsIgnoreCase(status);
    }

    private void applyVideoMetadata(ShoppingSession session, String uploadStatus,
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

    /** 仅运营补货（Ops 页开门）跳过识别结算；消费者账号走正常结算。 */
    private boolean isRestockSession(ShoppingSession session) {
        return DeviceValidationService.isRestockSession(session);
    }

    @Transactional(readOnly = true)
    public SessionDto getSession(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        return toDto(session);
    }

    @Transactional(readOnly = true)
    public OrderDto getSessionOrder(Long userId, String sessionId) {
        getSession(userId, sessionId);
        return settlementService.getOrderBySession(sessionId);
    }

    /** 开门指令已下发但设备未响应时，自动释放会话与设备占用。 */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void expireStaleOpeningSessions() {
        Instant cutoff = Instant.now().minus(OPENING_EXPIRE_SECONDS, ChronoUnit.SECONDS);
        repository.findAll().stream()
                .filter(s -> s.getState() == SessionState.OPENING || s.getState() == SessionState.CREATED)
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isBefore(cutoff))
                .forEach(s -> {
                    s.setState(SessionState.CANCELLED);
                    repository.save(s);
                    cabinetMetrics.recordSessionState(SessionState.CANCELLED);
                    log.warn("opening session expired session={} device={}", s.getSessionId(), s.getDeviceId());
                });
    }

    private void requireSessionOwner(Long userId, ShoppingSession session) {
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCESS_DENIED);
        }
    }

    void transition(ShoppingSession session, SessionState target) {
        if (!session.getState().canTransitionTo(target)) {
            cabinetMetrics.recordDoorOpen(false);
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        session.setState(target);
        repository.save(session);
        cabinetMetrics.recordSessionState(target);
        domainEventPublisher.publish("SessionStateChanged", session.getSessionId(),
                Map.of("state", target.name(), "deviceId", session.getDeviceId()));
        if (target == SessionState.COMPLETED && session.getCloseTime() != null) {
            long ms = ChronoUnit.MILLIS.between(session.getCloseTime(), Instant.now());
            cabinetMetrics.recordRecognizeMs(ms);
        }
    }

    private String generateSessionId() {
        return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private SessionDto toDto(ShoppingSession s) {
        String payChannel = isRestockSession(s) ? null : PayChannels.BALANCE;
        return new SessionDto(
                s.getSessionId(), s.getUserId(), s.getDeviceId(), s.getState(),
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(), s.getCreatedAt(),
                s.getFailReason(), payChannel
        );
    }
}
