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
import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 补货开门后未关门/未完成任务时的占柜超时（避免挡消费者）。 */
    private static final long RESTOCK_SHOPPING_EXPIRE_MINUTES = 30;
    /** 演示关门：无重力证据时注入 1 件演示取货，配合 mock 结算走 PAID。 */
    private static final String DEMO_CLOSE_GRAVITY_JSON =
            "[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]";
    private static final EnumSet<SessionState> ACTIVE_STATES = EnumSet.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.WAITING_UPLOAD, SessionState.RECOGNIZING, SessionState.SETTLING);
    private static final EnumSet<SessionState> RESTOCK_CLOSEABLE_STATES = EnumSet.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.WAITING_UPLOAD, SessionState.RECOGNIZING, SessionState.SETTLING);

    private final ShoppingSessionMapper repository;
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
    private final OpsExceptionService opsExceptionService;
    private final UserInfoMapper userInfoRepository;
    private final CabinetOrderMapper orderRepository;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;

    @Autowired
    private ScheduledTaskService taskService;

    public SessionService(ShoppingSessionMapper repository,
                          DeviceServiceClient deviceClient,
                          UserValidationService userValidationService,
                          DeviceValidationService deviceValidationService,
                          SettlementService settlementService,
                          VisionAsyncProperties visionAsyncProperties,
                          CabinetMetrics cabinetMetrics,
                          DomainEventPublisher domainEventPublisher,
                          GravitySettlementHelper gravityHelper,
                          RestockSnapshotService restockSnapshotService,
                          @Lazy SessionService self,
                          OpsExceptionService opsExceptionService,
                          UserInfoMapper userInfoRepository,
                          CabinetOrderMapper orderRepository,
                          @Lazy DisputeService disputeService,
                          ConsumerPreauthService consumerPreauthService) {
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
        this.opsExceptionService = opsExceptionService;
        this.userInfoRepository = userInfoRepository;
        this.orderRepository = orderRepository;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
    }

    @Transactional
    public SessionDto createSession(Long userId, CreateSessionRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (idempotencyKey != null) {
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> validateIdempotentReplay(userId, request.deviceId(), existing))
                    .orElseGet(() -> doCreateSession(userId, request));
        }
        return doCreateSession(userId, request);
    }

    private SessionDto doCreateSession(Long userId, CreateSessionRequest request) {
        String entryChannel = resolveEntryChannel(userId, request.entryChannel());
        userValidationService.validateCanOpenDoor(userId, request.deviceId(), entryChannel);
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        session.setState(SessionState.CREATED);
        session.setEntryChannel(entryChannel);
        session.setIdempotencyKey(normalizeIdempotencyKey(request.idempotencyKey()));
        // 先强制写入唯一幂等键，再改变状态和下发开门命令。并发重复请求会在这里失败，
        // 不会出现两个事务都先向同一台柜机发送开门命令、最后才在提交时发现冲突。
        repository.saveAndFlush(session);

        boolean passwordFree = userValidationService.isPasswordFreeReady(userId, entryChannel);
        consumerPreauthService.freezeForOpen(session, passwordFree);

        transition(session, SessionState.OPENING);
        deviceClient.requestOpenDoor(session.getSessionId(), session.getDeviceId(), userId, false);

        return toDto(session);
    }

    /**
     * 开发本地识别测试：创建会话并直接进入 SHOPPING，不下发 MQTT 开门指令。
     */
    @Transactional
    public SessionDto createSessionForDevTest(Long userId, CreateSessionRequest request) {
        String entryChannel = resolveEntryChannel(userId, request.entryChannel());
        userValidationService.validateCanOpenDoor(userId, request.deviceId(), entryChannel);
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        session.setState(SessionState.CREATED);
        session.setEntryChannel(entryChannel);
        repository.save(session);

        boolean passwordFree = userValidationService.isPasswordFreeReady(userId, entryChannel);
        consumerPreauthService.freezeForOpen(session, passwordFree);

        transition(session, SessionState.OPENING);
        session.setOpenTime(Instant.now());
        transition(session, SessionState.SHOPPING);
        log.info("dev test session shopping session={} device={} channel={}",
                session.getSessionId(), request.deviceId(), entryChannel);
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
            cabinetMetrics.recordSettlementFailure();
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
            if (session != null && isOpsRemoteSession(session)) {
                return afterDoor;
            }
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

    /**
     * 演示/联调用关门结算：无柜机硬件时由用户端主动触发关门，走同一套结算链路。
     * 仅允许本人正在 SHOPPING 的会话，且由 Controller 按 mockEnabled 开关放行。
     * 若会话尚无重力扣减证据，注入演示取货（SKU-DEMO-001 ×1），以便本地 mock 可直达 PAID。
     */
    @Transactional
    public SessionDto demoCloseSession(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND);
        }
        if (session.getState() != SessionState.SHOPPING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不可关门结算");
        }
        String gravityJson = null;
        if (gravityHelper.toRecognizedItems(session.getGravityDeltas()).isEmpty()) {
            gravityJson = DEMO_CLOSE_GRAVITY_JSON;
            log.info("demo-close injects sample gravity session={} sku=SKU-DEMO-001", sessionId);
        }
        return self.handleDoorEvent(new DoorEventRequest(
                session.getSessionId(),
                session.getDeviceId(),
                DoorState.CLOSED,
                System.currentTimeMillis(),
                null,
                null,
                null,
                null,
                gravityJson));
    }

    @Transactional
    public SessionDto applyDoorEvent(DoorEventRequest event) {
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
            default -> toDto(session);
        };
    }

    @Transactional
    public SessionDto attachVideo(VideoAttachRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(request.sessionId())
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
            if (isOpsRemoteSession(session)) {
                transition(session, SessionState.COMPLETED);
                return toDto(session);
            }
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

        if (isOpsRemoteSession(session)) {
            // 运维开门：关门即完成，不识别、不结算；有录像则保留供审计
            transition(session, SessionState.COMPLETED);
            log.info("ops remote door closed session={} device={}", session.getSessionId(), session.getDeviceId());
            return toDto(session);
        }

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
            cabinetMetrics.recordSettlementSuccess();
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                        session.getSessionId(), order.orderId(), session.getUserId(),
                        "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "识别结果需人工审核", e.getMessage());
            log.warn("session disputed session={}", session.getSessionId());
            cabinetMetrics.recordSettlementFailure();
            return toDto(session);
        } catch (BalanceInsufficientException e) {
            // 兼容旧路径：若结算仍抛余额不足且未落单，则进争议
            session.setFailReason(e.getMessage());
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "结算余额不足", e.getMessage());
            log.warn("session balance insufficient session={}", session.getSessionId());
            cabinetMetrics.recordSettlementFailure();
            return toDto(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                transition(session, SessionState.DISPUTED);
                opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                        session.getSessionId(), session.getOrderId(), session.getUserId(),
                        "结算余额不足", e.getReason());
                return toDto(session);
            }
            session.setFailReason(e.getReason());
            transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("session failed session={} reason={}", session.getSessionId(), e.getReason());
            cabinetMetrics.recordSettlementFailure();
            return toDto(session);
        } catch (RestClientException e) {
            log.error("vision/settle remote call failed session={}", session.getSessionId(), e);
            opsExceptionService.report("RECOGNITION_UNAVAILABLE", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "识别或结算服务不可用", e.getMessage());
            transition(session, SessionState.FAILED);
            return toDto(session);
        } catch (RuntimeException e) {
            log.error("settle failed session={}", session.getSessionId(), e);
            cabinetMetrics.recordSettlementFailure();
            opsExceptionService.report("SETTLEMENT_FAILED", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "订单结算失败", e.getMessage());
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
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                        session.getSessionId(), order.orderId(), session.getUserId(),
                        "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "识别结果需人工审核", e.getMessage());
            log.warn("async session disputed session={}", sessionId);
            return;
        } catch (BalanceInsufficientException e) {
            session.setFailReason(e.getMessage());
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                    session.getSessionId(), session.getOrderId(), session.getUserId(),
                    "结算余额不足", e.getMessage());
            log.warn("async session balance insufficient session={}", sessionId);
            return;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                transition(session, SessionState.DISPUTED);
                log.warn("async session disputed session={}", sessionId);
                return;
            }
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                transition(session, SessionState.DISPUTED);
                opsExceptionService.report("BALANCE_INSUFFICIENT", "HIGH", session.getDeviceId(),
                        session.getSessionId(), session.getOrderId(), session.getUserId(),
                        "结算余额不足", e.getReason());
                log.warn("async session balance insufficient session={}", sessionId);
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

    private boolean isOpsRemoteSession(ShoppingSession session) {
        return DeviceValidationService.isOpsRemoteSession(session);
    }

    @Transactional(readOnly = true)
    public SessionDto getSession(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        return toDto(session);
    }

    @Transactional(readOnly = true)
    public SessionDto getActiveSession(Long userId) {
        return repository.findFirstByUserIdAndStateInOrderByCreatedAtDesc(userId, ACTIVE_STATES)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public SessionDto cancelSession(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        if (session.getState() == SessionState.CANCELLED) {
            return toDto(session);
        }
        if (session.getState() != SessionState.CREATED && session.getState() != SessionState.OPENING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        consumerPreauthService.releaseIfFrozen(session);
        transition(session, SessionState.CANCELLED);
        log.info("consumer cancelled opening session={} device={}", sessionId, session.getDeviceId());
        return toDto(session);
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
            // 任务结束时尽力回写实测，减少货道差异误报
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
                    Map.of("deviceId", session.getDeviceId(), "taskId", String.valueOf(taskId),
                            "reason", failReason));
            log.info("restock session auto-closed session={} task={} reason={}",
                    session.getSessionId(), taskId, failReason);
        }
        return open.size();
    }

    /** 运营兜底：终止异常活跃会话，使设备重新可用。调用方必须完成权限、二次确认和审计。 */
    @Transactional
    public SessionDto forceCancelForOperations(String sessionId, String reason) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!ACTIVE_STATES.contains(session.getState())) return toDto(session);
        consumerPreauthService.releaseIfFrozen(session);
        session.setFailReason(reason == null ? "运营终止会话" : reason.trim());
        session.setState(SessionState.CANCELLED);
        repository.save(session);
        cabinetMetrics.recordSessionState(SessionState.CANCELLED);
        domainEventPublisher.publish("SessionForceCancelled", sessionId,
                Map.of("deviceId", session.getDeviceId(), "reason", session.getFailReason()));
        log.warn("operations force cancelled session={} device={} reason={}",
                sessionId, session.getDeviceId(), session.getFailReason());
        return toDto(session);
    }

    /** 运营重试识别/结算。订单和扣款仍由 SettlementService 的会话幂等约束保护。 */
    @Transactional
    public SessionDto retryForOperations(String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() == SessionState.COMPLETED) return toDto(session);
        if (!EnumSet.of(SessionState.FAILED, SessionState.DISPUTED, SessionState.RECOGNIZING,
                SessionState.SETTLING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不支持重新识别或结算");
        }
        boolean hasVideo = session.getVideoUri() != null && !session.getVideoUri().isBlank();
        boolean hasGravity = session.getGravityDeltas() != null && !session.getGravityDeltas().isBlank();
        if (!hasVideo && !hasGravity && session.getOrderId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话缺少视频或重力数据，不能自动重试");
        }
        session.setFailReason(null);
        session.setState(SessionState.RECOGNIZING);
        repository.save(session);
        cabinetMetrics.recordSessionState(SessionState.RECOGNIZING);
        return settleSession(session);
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
        long start = System.nanoTime();
        if (!taskService.tryBegin("session-opening-expire", 600)) {
            return;
        }
        boolean failed = false;
        try {
        Instant cutoff = Instant.now().minus(OPENING_EXPIRE_SECONDS, ChronoUnit.SECONDS);
        repository.findByStateInAndCreatedAtBefore(
                        List.of(SessionState.OPENING, SessionState.CREATED), cutoff, 500)
                .forEach(s -> {
                    consumerPreauthService.releaseIfFrozen(s);
                    s.setState(SessionState.CANCELLED);
                    repository.save(s);
                    cabinetMetrics.recordSessionState(SessionState.CANCELLED);
                    opsExceptionService.report("OPEN_TIMEOUT", "HIGH", s.getDeviceId(), s.getSessionId(),
                            s.getOrderId(), s.getUserId(), "开门超时", "开门命令在90秒内未得到设备响应");
                    log.warn("opening session expired session={} device={}", s.getSessionId(), s.getDeviceId());
                });
        } catch (Exception e) {
            failed = true;
            taskService.finish("session-opening-expire", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("session-opening-expire", "SUCCESS", null, start);
            }
        }
    }

    /** 补货会话长时间停在购物态：超时清理，避免挡消费者开门。 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleRestockShoppingSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("session-restock-expire", 600)) {
            return;
        }
        boolean failed = false;
        try {
        Instant cutoff = Instant.now().minus(RESTOCK_SHOPPING_EXPIRE_MINUTES, ChronoUnit.MINUTES);
        repository.findByStateInAndUpdatedAtBefore(
                        List.of(SessionState.SHOPPING, SessionState.WAITING_UPLOAD), cutoff, 500)
                .stream()
                .filter(DeviceValidationService::isRestockSession)
                .forEach(s -> {
                    s.setFailReason("补货会话超时自动关闭");
                    if (s.getCloseTime() == null) {
                        s.setCloseTime(Instant.now());
                    }
                    s.setState(SessionState.CANCELLED);
                    repository.save(s);
                    cabinetMetrics.recordSessionState(SessionState.CANCELLED);
                    opsExceptionService.report("RESTOCK_SESSION_TIMEOUT", "MEDIUM", s.getDeviceId(),
                            s.getSessionId(), s.getOrderId(), s.getUserId(),
                            "补货会话超时", "补货开门后超过" + RESTOCK_SHOPPING_EXPIRE_MINUTES + "分钟未结束");
                    log.warn("restock shopping session expired session={} device={}",
                            s.getSessionId(), s.getDeviceId());
                });
        } catch (Exception e) {
            failed = true;
            taskService.finish("session-restock-expire", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("session-restock-expire", "SUCCESS", null, start);
            }
        }
    }

    /** 识别/结算长时间无结果：转争议，避免占柜机与前端一直卡在「识别中」。 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleRecognizingSessions() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("session-recognizing-expire", 600)) {
            return;
        }
        boolean failed = false;
        try {
        Instant cutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        repository.findByStateInAndUpdatedAtBefore(
                        List.of(SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING),
                        cutoff, 500)
                .forEach(s -> {
                    Instant anchor = s.getCloseTime() != null ? s.getCloseTime()
                            : (s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt());
                    if (anchor == null || !anchor.isBefore(cutoff)) {
                        return;
                    }
                    try {
                        // 补货会话不走消费者争议：超时尽力快照后关闭，避免误建争议单
                        if (DeviceValidationService.isRestockSession(s)) {
                            closeStaleRestockRecognizing(s);
                            return;
                        }
                        SessionState from = s.getState();
                        String failReason = "识别超时，已转人工审核，本次暂未扣款";
                        if (from.canTransitionTo(SessionState.DISPUTED)) {
                            s.setFailReason(failReason);
                            transition(s, SessionState.DISPUTED);
                            try {
                                disputeService.createTimeoutTicket(s, failReason);
                            } catch (Exception ticketEx) {
                                log.warn("超时争议单创建失败 session={}", s.getSessionId(), ticketEx);
                            }
                        } else if (from.canTransitionTo(SessionState.FAILED)) {
                            s.setFailReason("识别超时");
                            transition(s, SessionState.FAILED);
                        } else {
                            s.setFailReason("识别超时");
                            s.setState(SessionState.FAILED);
                            repository.save(s);
                            cabinetMetrics.recordSessionState(SessionState.FAILED);
                        }
                        opsExceptionService.report("RECOGNITION_TIMEOUT", "HIGH", s.getDeviceId(),
                                s.getSessionId(), s.getOrderId(), s.getUserId(),
                                "识别超时", "关门后超过10分钟未完成识别结算");
                        log.warn("识别超时会话已升级 session={} from={} to={}",
                                s.getSessionId(), from, s.getState());
                    } catch (Exception e) {
                        log.warn("识别超时升级失败 session={}", s.getSessionId(), e);
                    }
                });
        } catch (Exception e) {
            failed = true;
            taskService.finish("session-recognizing-expire", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("session-recognizing-expire", "SUCCESS", null, start);
            }
        }
    }

    private void closeStaleRestockRecognizing(ShoppingSession session) {
        session.setFailReason("补货识别超时自动关闭");
        if (session.getCloseTime() == null) {
            session.setCloseTime(Instant.now());
        }
        try {
            restockSnapshotService.applySnapshot(session);
        } catch (Exception e) {
            log.warn("补货超时快照失败 session={}，仍关闭会话", session.getSessionId(), e);
        }
        session.setState(SessionState.COMPLETED);
        repository.save(session);
        cabinetMetrics.recordSessionState(SessionState.COMPLETED);
        opsExceptionService.report("RESTOCK_RECOGNITION_TIMEOUT", "MEDIUM", session.getDeviceId(),
                session.getSessionId(), session.getOrderId(), session.getUserId(),
                "补货识别超时", "补货关门后超过10分钟未完成货道快照");
        log.warn("restock recognizing session expired session={} device={}",
                session.getSessionId(), session.getDeviceId());
    }

    private void requireSessionOwner(Long userId, ShoppingSession session) {
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCESS_DENIED);
        }
    }

    private SessionDto validateIdempotentReplay(Long userId, String deviceId, ShoppingSession session) {
        if (!session.getUserId().equals(userId) || !session.getDeviceId().equalsIgnoreCase(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等键已用于其他开门请求");
        }
        return toDto(session);
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        return key.trim();
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
        return BizIds.nextNumeric();
    }

    private SessionDto toDto(ShoppingSession s) {
        String payChannel = null;
        if (!isRestockSession(s)) {
            // 已生成订单时展示真实扣款渠道，避免扫码入口渠道（WECHAT）被当成余额支付渠道
            if (s.getOrderId() != null && !s.getOrderId().isBlank()) {
                payChannel = orderRepository.findById(s.getOrderId())
                        .map(CabinetOrder::getPayChannel)
                        .orElse(null);
            }
            if (payChannel == null || payChannel.isBlank()) {
                payChannel = s.getEntryChannel();
            }
        }
        return new SessionDto(
                s.getSessionId(), s.getUserId(), s.getDeviceId(), s.getState(),
                s.getOpenTime(), s.getCloseTime(), s.getOrderId(), s.getCreatedAt(),
                s.getFailReason(), payChannel
        );
    }

    private String resolveEntryChannel(Long userId, String requested) {
        String entry = PayChannels.normalizeEntryChannel(requested);
        if (entry != null) {
            return entry;
        }
        return userInfoRepository.findById(userId)
                .map(u -> PayChannels.normalizeEntryChannel(u.getPayPreferredChannel()))
                .orElse(null);
    }
}
