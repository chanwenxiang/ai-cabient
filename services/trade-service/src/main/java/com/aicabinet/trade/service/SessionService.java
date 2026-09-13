package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.common.dto.LiveCartDto;
import com.aicabinet.common.dto.LiveCartUpdateRequest;
import com.aicabinet.common.dto.OrderReadModel;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@Service
public class SessionService {
    private static final String BALANCE_INSUFFICIENT = "BALANCE_INSUFFICIENT";
    private static final String DEVICEID = "deviceId";
    private static final String LITERAL = "结算余额不足";

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final EnumSet<SessionState> ACTIVE_STATES = EnumSet.of(
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
    private final SessionOpenService sessionOpenService;
    private final SessionRestockService sessionRestockService;
    private final SessionDoorService sessionDoorService;
    private final SessionService self;
    private final OpsExceptionService opsExceptionService;
    private final UserInfoMapper userInfoRepository;
    private final CabinetOrderMapper orderRepository;
    private final InventoryLotService inventoryLotService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DistributedLockService distributedLockService;
    private final ObjectMapper objectMapper;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final ApiRateLimitService apiRateLimitService;

    public SessionService(ShoppingSessionMapper repository,
                          DeviceServiceClient deviceClient,
                          UserValidationService userValidationService,
                          DeviceValidationService deviceValidationService,
                          SettlementService settlementService,
                          VisionAsyncProperties visionAsyncProperties,
                          CabinetMetrics cabinetMetrics,
                          DomainEventPublisher domainEventPublisher,
                          GravitySettlementHelper gravityHelper,
                          SessionOpenService sessionOpenService,
                          SessionRestockService sessionRestockService,
                          SessionDoorService sessionDoorService,
                          @Lazy SessionService self,
                          OpsExceptionService opsExceptionService,
                          UserInfoMapper userInfoRepository,
                          CabinetOrderMapper orderRepository,
                          InventoryLotService inventoryLotService,
                          ConsumerPreauthService consumerPreauthService,
                          DistributedLockService distributedLockService,
                          ObjectMapper objectMapper,
                          DisplaySnapshotHelper displaySnapshotHelper,
                          ApiRateLimitService apiRateLimitService) {
        this.repository = repository;
        this.deviceClient = deviceClient;
        this.userValidationService = userValidationService;
        this.deviceValidationService = deviceValidationService;
        this.settlementService = settlementService;
        this.visionAsyncProperties = visionAsyncProperties;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.gravityHelper = gravityHelper;
        this.sessionOpenService = sessionOpenService;
        this.sessionRestockService = sessionRestockService;
        this.sessionDoorService = sessionDoorService;
        this.self = self;
        this.opsExceptionService = opsExceptionService;
        this.userInfoRepository = userInfoRepository;
        this.orderRepository = orderRepository;
        this.inventoryLotService = inventoryLotService;
        this.consumerPreauthService = consumerPreauthService;
        this.distributedLockService = distributedLockService;
        this.objectMapper = objectMapper;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.apiRateLimitService = apiRateLimitService;
    }

    /** 无外层长事务：落库短事务与 MQTT 开门分离。 */
    public SessionDto createSession(Long userId, CreateSessionRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (idempotencyKey != null) {
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> validateIdempotentReplay(userId, request.deviceId(), existing))
                    .orElseGet(() -> runWithDeviceOpenLock(request.deviceId(),
                            () -> createSessionAndRequestOpen(userId, request)));
        }
        return runWithDeviceOpenLock(request.deviceId(), () -> createSessionAndRequestOpen(userId, request));
    }

    private SessionDto createSessionAndRequestOpen(Long userId, CreateSessionRequest request) {
        // 会话创建与开门分计：防刷会话与防刷开门互补（风控小时开门上限仍生效）
        apiRateLimitService.assertSessionCreateAllowed(userId);
        apiRateLimitService.assertOpenDoorAllowed(userId, request.deviceId());
        SessionDto dto = sessionOpenService.persistConsumerOpeningSession(userId, request);
        try {
            deviceClient.requestOpenDoor(dto.sessionId(), request.deviceId(), userId, false);
        } catch (ResponseStatusException e) {
            sessionOpenService.markOpenDoorFailed(dto.sessionId(), "开门指令下发失败");
            throw e;
        } catch (Exception e) {
            sessionOpenService.markOpenDoorFailed(dto.sessionId(), "开门指令下发失败");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开门指令下发失败（请确认 device-service 在线）", e);
        }
        return dto;
    }

    /**
     * 开发本地识别测试：创建会话并直接进入 SHOPPING，不下发 MQTT 开门指令。
     */
    @Transactional
    public SessionDto createSessionForDevTest(Long userId, CreateSessionRequest request) {
        return runWithDeviceOpenLock(request.deviceId(), () -> doCreateSessionForDevTest(userId, request));
    }

    private SessionDto doCreateSessionForDevTest(Long userId, CreateSessionRequest request) {
        String entryChannel = resolveEntryChannel(userId, request.entryChannel());
        userValidationService.validateCanOpenDoor(userId, request.deviceId(), entryChannel);
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        displaySnapshotHelper.applySessionDeviceName(session);
        session.setState(SessionState.CREATED);
        session.setEntryChannel(entryChannel);
        session.setPreferredCouponId(request.preferredCouponId());
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

    /** 开发上传识别：用真实 vision 结果结算，不走 mock 兜底。无外层长事务。 */
    public SessionDto completeDevUploadRecognition(String sessionId,
                                                   VisionServiceClient.RecognitionResult recognition) {
        return runWithSessionLifeLock(sessionId, () -> doCompleteDevUploadRecognition(sessionId, recognition));
    }

    private SessionDto doCompleteDevUploadRecognition(String sessionId,
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
            OrderReadModel order = settlementService.processRecognitionResult(session, recognition, false);
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

    /** 门事件入口；关门落库与结算编排见 {@link SessionDoorService}。 */
    public SessionDto handleDoorEvent(DoorEventRequest event) {
        return sessionDoorService.handleDoorEvent(event);
    }

    /** 仅落门事件与会话态（短事务）；结算由 {@link #handleDoorEvent} 编排。 */
    public SessionDto applyDoorEvent(DoorEventRequest event) {
        return sessionDoorService.applyDoorEvent(event);
    }

    /**
     * 演示/联调用关门结算：无柜机硬件时由用户端主动触发关门。
     * 仅允许本人正在 SHOPPING 的会话，且由 Controller 按 mockEnabled 开关放行。
     * 扣款以会话购物车（点选同步的重力证据）为准；未选商品则零元结案，不走视觉 mock（避免「未选却出牛奶审单」）。
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
        // 未点选：直接零元完成，禁止视觉 mock 注入商品进入争议/扣款
        if (gravityHelper.toRecognizedItems(session.getGravityDeltas()).isEmpty()) {
            return self.completeDemoZeroSettle(userId, sessionId);
        }
        return sessionDoorService.handleDoorEvent(new DoorEventRequest(
                session.getSessionId(),
                session.getDeviceId(),
                DoorState.CLOSED,
                System.currentTimeMillis(),
                null,
                null,
                null,
                null,
                null));
    }

    /**
     * 演示关门且购物车为空：零元订单并完结会话（与文案「未选则不扣款」一致）。
     */
    @Transactional
    public SessionDto completeDemoZeroSettle(Long userId, String sessionId) {
        return runWithSessionLifeLock(sessionId, () -> {
            ShoppingSession session = repository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            if (!session.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND);
            }
            if (session.getState() != SessionState.SHOPPING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不可关门结算");
            }
            log.info("demo-close zero-settle session={} device={}", sessionId, session.getDeviceId());
            transition(session, SessionState.RECOGNIZING);
            transition(session, SessionState.SETTLING);
            OrderReadModel order = settlementService.settleManual(session, List.of());
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            cabinetMetrics.recordSettlementSuccess();
            return toDto(session);
        });
    }

    /**
     * 先短事务落视频与会话态，再事务外结算（同步 vision 或异步 Kafka）。
     */
    public SessionDto attachVideo(VideoAttachRequest request) {
        return runWithSessionLifeLock(request.sessionId(), () -> {
            SessionDto afterAttach = self.persistAttachedVideo(request);
            ShoppingSession session = repository.findById(request.sessionId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            if (session.getState() != SessionState.RECOGNIZING) {
                return afterAttach;
            }
            if (isOpsRemoteSession(session)) {
                return afterAttach;
            }
            if (isRestockSession(session)) {
                return sessionRestockService.finishRestockSnapshot(session.getSessionId());
            }
            return settleSession(session);
        });
    }

    @Transactional
    public SessionDto persistAttachedVideo(VideoAttachRequest request) {
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
        if (session.getState() == SessionState.RECOGNIZING && isOpsRemoteSession(session)) {
            transition(session, SessionState.COMPLETED);
        }
        return toDto(session);
    }

    /** 补货关门后：视觉/重力快照回写货道实测，不创建订单。无外层长事务包裹 vision HTTP。 */
    public SessionDto finishRestockSnapshot(String sessionId) {
        return sessionRestockService.finishRestockSnapshot(sessionId);
    }

    @Transactional
    public ShoppingSession beginRestockSnapshot(String sessionId) {
        return sessionRestockService.beginRestockSnapshot(sessionId);
    }

    @Transactional
    public SessionDto completeRestockSnapshot(String sessionId) {
        return sessionRestockService.completeRestockSnapshot(sessionId);
    }

    @Transactional
    public SessionDto failRestockSnapshot(String sessionId) {
        return sessionRestockService.failRestockSnapshot(sessionId);
    }

    @Transactional
    public SessionDto attachGravityDeltas(GravityDeltaRequest request) {
        return runWithSessionLifeLock(request.sessionId(), () -> doAttachGravityDeltas(request));
    }

    private SessionDto doAttachGravityDeltas(GravityDeltaRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(request.sessionId())
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
        return runWithSessionLifeLock(sessionId, () -> doUpdateSessionCart(userId, sessionId, request));
    }

    private SessionDto doUpdateSessionCart(Long userId, String sessionId, SessionCartRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        if (!EnumSet.of(SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        List<GravityDeltaRequest.GravityDeltaItem> deltas = (request.items() == null ? List.<SessionCartRequest.CartItem>of() : request.items())
                .stream()
                .filter(item -> item.qty() > 0)
                .map(item -> {
                    // 与货道账面同源：可售批次（ON_SALE / NEAR_EXPIRY）
                    int available = inventoryLotService.availableSellableQuantity(
                            session.getDeviceId(), item.skuId());
                    if (item.qty() > available) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "库存不足 sku=" + item.skuId() + " 当前=" + available + " 选购=" + item.qty());
                    }
                    return new GravityDeltaRequest.GravityDeltaItem(item.skuId(), -item.qty(), null);
                })
                .toList();
        session.setGravityDeltas(deltas.isEmpty() ? null : gravityHelper.fromRequestItems(deltas));
        repository.save(session);
        log.info("session cart updated session={} items={}", sessionId, deltas.size());
        return toDto(session);
    }

    /**
     * 第三方识别推送实时购物车（internal）。仅更新展示字段，不触发扣款。
     */
    @Transactional
    public LiveCartDto updateLiveCartFromVision(String sessionId, LiveCartUpdateRequest request) {
        return runWithSessionLifeLock(sessionId, () -> doUpdateLiveCartFromVision(sessionId, request));
    }

    private LiveCartDto doUpdateLiveCartFromVision(String sessionId, LiveCartUpdateRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!EnumSet.of(SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        String mode = request == null ? "REPLACE" : request.resolvedMode();
        Map<String, LiveCartDto.LiveCartLine> bySku = seedCartFromExisting(session, mode);
        List<LiveCartUpdateRequest.LiveCartItem> incoming =
                request == null || request.items() == null ? List.of() : request.items();
        mergeIncomingCartItems(bySku, incoming, mode);
        if ("REPLACE".equals(mode) && incoming.isEmpty()) {
            bySku.clear();
        }
        List<LiveCartDto.LiveCartLine> lines = new ArrayList<>(bySku.values());
        session.setLiveCart(writeLiveCartJson(lines));
        repository.save(session);
        log.info("live cart updated session={} mode={} lines={}", sessionId, mode, lines.size());
        return toLiveCartDto(sessionId, lines);
    }

    private Map<String, LiveCartDto.LiveCartLine> seedCartFromExisting(ShoppingSession session, String mode) {
        Map<String, LiveCartDto.LiveCartLine> bySku = new LinkedHashMap<>();
        if (!"DELTA".equals(mode)) {
            return bySku;
        }
        for (LiveCartDto.LiveCartLine existing : parseLiveCartLines(session.getLiveCart())) {
            bySku.put(existing.skuId(), existing);
        }
        return bySku;
    }

    private void mergeIncomingCartItems(Map<String, LiveCartDto.LiveCartLine> bySku,
                                        List<LiveCartUpdateRequest.LiveCartItem> incoming,
                                        String mode) {
        for (LiveCartUpdateRequest.LiveCartItem item : incoming) {
            if (item == null || item.skuId() == null || item.skuId().isBlank()) {
                continue;
            }
            if ("DELTA".equals(mode)) {
                applyDeltaCartItem(bySku, item);
            } else {
                applyReplaceCartItem(bySku, item);
            }
        }
    }

    private static void applyDeltaCartItem(Map<String, LiveCartDto.LiveCartLine> bySku,
                                           LiveCartUpdateRequest.LiveCartItem item) {
        String sku = item.skuId().trim();
        LiveCartDto.LiveCartLine prev = bySku.get(sku);
        int prevQty = prev == null ? 0 : prev.quantity();
        int nextQty = Math.max(0, prevQty + item.quantity());
        if (nextQty <= 0) {
            bySku.remove(sku);
            return;
        }
        int unit;
        if (item.unitPriceCents() != null && item.unitPriceCents() > 0) {
            unit = item.unitPriceCents();
        } else if (prev != null) {
            unit = prev.unitPriceCents();
        } else {
            unit = 0;
        }
        String name;
        if (item.skuName() != null && !item.skuName().isBlank()) {
            name = item.skuName();
        } else if (prev != null) {
            name = prev.skuName();
        } else {
            name = sku;
        }
        bySku.put(sku, new LiveCartDto.LiveCartLine(sku, name, nextQty, unit, unit * nextQty));
    }

    private static void applyReplaceCartItem(Map<String, LiveCartDto.LiveCartLine> bySku,
                                             LiveCartUpdateRequest.LiveCartItem item) {
        if (item.quantity() <= 0) {
            return;
        }
        String sku = item.skuId().trim();
        int unit = item.unitPriceCents() == null ? 0 : Math.max(0, item.unitPriceCents());
        String name = item.skuName() == null || item.skuName().isBlank() ? sku : item.skuName();
        bySku.put(sku, new LiveCartDto.LiveCartLine(sku, name, item.quantity(), unit, unit * item.quantity()));
    }

    @Transactional(readOnly = true)
    public LiveCartDto getLiveCart(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        requireSessionOwner(userId, session);
        return toLiveCartDto(sessionId, parseLiveCartLines(session.getLiveCart()));
    }

    private List<LiveCartDto.LiveCartLine> parseLiveCartLines(String json) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return List.of();
        }
        try {
            List<LiveCartDto.LiveCartLine> lines = objectMapper.readValue(json, new TypeReference<>() {});
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            log.warn("parse live_cart failed", e);
            return List.of();
        }
    }

    private String writeLiveCartJson(List<LiveCartDto.LiveCartLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalStateException("serialize live_cart failed", e);
        }
    }

    private static LiveCartDto toLiveCartDto(String sessionId, List<LiveCartDto.LiveCartLine> lines) {
        int qty = 0;
        int amount = 0;
        for (LiveCartDto.LiveCartLine line : lines) {
            qty += line.quantity();
            amount += line.lineAmountCents();
        }
        return new LiveCartDto(sessionId, lines, qty, amount);
    }

    /**
     * 关门事务提交后再结算，避免 vision/扣款失败把「已关门」回滚掉。
     * 无外层长事务：分布式锁内调用 settle / 异步投递。
     */
    public SessionDto settleAfterClose(String sessionId) {
        return runWithSessionLifeLock(sessionId, () -> {
            ShoppingSession session = repository.findById(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            if (session.getState() != SessionState.RECOGNIZING) {
                return toDto(session);
            }
            return settleSession(session);
        });
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
            OrderReadModel order = settlementService.settle(session);
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            log.info("session completed session={} order={}", session.getSessionId(), order.orderId());
            cabinetMetrics.recordSettlementSuccess();
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), order.orderId(), session.getUserId()), "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "识别结果需人工审核", e.getMessage());
            log.warn("session disputed session={}", session.getSessionId());
            cabinetMetrics.recordSettlementFailure();
            return toDto(session);
        } catch (BalanceInsufficientException e) {
            // 兼容旧路径：若结算仍抛余额不足且未落单，则进争议
            session.setFailReason(e.getMessage());
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), LITERAL, e.getMessage());
            log.warn("session balance insufficient session={}", session.getSessionId());
            cabinetMetrics.recordSettlementFailure();
            return toDto(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                transition(session, SessionState.DISPUTED);
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), LITERAL, e.getReason());
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
            opsExceptionService.report("RECOGNITION_UNAVAILABLE", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "识别或结算服务不可用", e.getMessage());
            transition(session, SessionState.FAILED);
            return toDto(session);
        } catch (RuntimeException e) {
            log.error("settle failed session={}", session.getSessionId(), e);
            cabinetMetrics.recordSettlementFailure();
            opsExceptionService.report("SETTLEMENT_FAILED", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "订单结算失败", e.getMessage());
            session.setFailReason(ApiMessages.INTERNAL_ERROR);
            if (session.getState().canTransitionTo(SessionState.FAILED)) {
                transition(session, SessionState.FAILED);
            }
            repository.save(session);
            return toDto(session);
        }
        return toDto(session);
    }

    /**
     * 异步识别结果回调：无外层长事务；会话态短事务与结算短事务分离（扣款已 NOT_SUPPORTED）。
     */
    public void completeAsyncRecognition(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        runWithSessionLifeLock(sessionId, () -> {
            doCompleteAsyncRecognition(sessionId, recognition);
            return null;
        });
    }

    private void doCompleteAsyncRecognition(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() != SessionState.RECOGNIZING) {
            log.warn("ignore async recognition session={} state={}", sessionId, session.getState());
            return;
        }
        transition(session, SessionState.SETTLING);
        try {
            OrderReadModel order = settlementService.processRecognitionResult(session, recognition);
            session.setOrderId(order.orderId());
            transition(session, SessionState.COMPLETED);
            log.info("async session completed session={} order={}", sessionId, order.orderId());
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), order.orderId(), session.getUserId()), "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), "识别结果需人工审核", e.getMessage());
            log.warn("async session disputed session={}", sessionId);
        } catch (BalanceInsufficientException e) {
            session.setFailReason(e.getMessage());
            transition(session, SessionState.DISPUTED);
            opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), LITERAL, e.getMessage());
            log.warn("async session balance insufficient session={}", sessionId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                transition(session, SessionState.DISPUTED);
                log.warn("async session disputed session={}", sessionId);
                return;
            }
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                transition(session, SessionState.DISPUTED);
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), LITERAL, e.getReason());
                log.warn("async session balance insufficient session={}", sessionId);
                return;
            }
            session.setFailReason(e.getReason());
            transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("async session failed session={} reason={}", sessionId, e.getReason());
        }
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
        return runWithSessionLifeLock(sessionId, () -> doCancelSession(userId, sessionId));
    }

    private SessionDto doCancelSession(Long userId, String sessionId) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
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
        return sessionRestockService.closeRestockSessionsForTask(taskId, reason);
    }

    /** 运营兜底：终止异常活跃会话，使设备重新可用。调用方必须完成权限、二次确认和审计。 */
    @Transactional
    public SessionDto forceCancelForOperations(String sessionId, String reason) {
        return runWithSessionLifeLock(sessionId, () -> doForceCancelForOperations(sessionId, reason));
    }

    private SessionDto doForceCancelForOperations(String sessionId, String reason) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!ACTIVE_STATES.contains(session.getState())) return toDto(session);
        consumerPreauthService.releaseIfFrozen(session);
        session.setFailReason(reason == null ? "运营终止会话" : reason.trim());
        session.setState(SessionState.CANCELLED);
        repository.save(session);
        cabinetMetrics.recordSessionState(SessionState.CANCELLED);
        domainEventPublisher.publish("SessionForceCancelled", sessionId,
                Map.of(DEVICEID, session.getDeviceId(), "reason", session.getFailReason()));
        log.warn("operations force cancelled session={} device={} reason={}",
                sessionId, session.getDeviceId(), session.getFailReason());
        return toDto(session);
    }

    /** 运营重试识别/结算。订单和扣款仍由 SettlementService 的会话幂等约束保护。 */
    public SessionDto retryForOperations(String sessionId) {
        return runWithSessionLifeLock(sessionId, () -> {
            self.resetSessionForRetry(sessionId);
            ShoppingSession session = repository.findById(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            return settleSession(session);
        });
    }

    @Transactional
    public void resetSessionForRetry(String sessionId) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() == SessionState.COMPLETED) {
            return;
        }
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
    }

    @Transactional(readOnly = true)
    public OrderReadModel getSessionOrder(Long userId, String sessionId) {
        self.getSession(userId, sessionId);
        return settlementService.getOrderBySession(sessionId);
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
                Map.of("state", target.name(), DEVICEID, session.getDeviceId()));
        if (target == SessionState.COMPLETED && session.getCloseTime() != null) {
            long ms = ChronoUnit.MILLIS.between(session.getCloseTime(), Instant.now());
            cabinetMetrics.recordRecognizeMs(ms);
        }
    }

    private String generateSessionId() {
        return BizIds.nextNumeric();
    }

    SessionDto toDto(ShoppingSession s) {
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
                s.getFailReason(), payChannel, s.getDeviceName()
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

    static String sessionLifeLockKey(String sessionId) {
        return "session:life:" + sessionId;
    }

    static String sessionOpenLockKey(String deviceId) {
        return "session:open:" + deviceId;
    }

    private <T> T runWithDeviceOpenLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(sessionOpenLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备开门处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(sessionOpenLockKey(deviceId));
        }
    }

    <T> T runWithSessionLifeLock(String sessionId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(sessionLifeLockKey(sessionId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException | DisputeRequiredException | BalanceInsufficientException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(sessionLifeLockKey(sessionId));
        }
    }
}
