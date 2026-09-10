package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.messaging.VisionRecognitionProducer;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
@Service
@SuppressWarnings("java:S6539")
public class SettlementService {
    private static final String GRAVITY_MISMATCH = "gravity-mismatch";
    private static final String GRAVITY_FILL = "gravity-fill";


    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final VisionServiceClient visionClient;
    private final DisputeService disputeService;
    private final ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer;
    private final RevenueSplitService revenueSplitService;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;
    private final InventoryService inventoryService;
    private final OrderPaymentService orderPaymentService;
    private final SettlementConfidenceService confidenceService;
    private final GravitySettlementHelper gravityHelper;
    private final DeviceValidationService deviceValidationService;
    private final MerchantSkuPricingService skuPricingService;
    private final UserValidationService userValidationService;
    private final VideoArchiveService videoArchiveService;
    private final SkuVisionEnrollmentService skuVisionEnrollmentService;
    private final CouponService couponService;
    private final MemberService memberService;
    private final RefundPolicyService refundPolicyService;
    private final NotificationService notificationService;
    private final DeviceSlotMapper slotRepository;
    private final ConsumerPreauthService consumerPreauthService;
    private final SystemConfigService systemConfigService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final SettlementService self;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final OrderViewAssembler orderViewAssembler;

    public SettlementService(ShoppingSessionMapper sessionRepository,
                             SkuCatalogMapper skuCatalogRepository,
                             CabinetOrderMapper orderRepository,
                             CabinetOrderLineMapper orderLineRepository,
                             VisionServiceClient visionClient,
                             @Lazy DisputeService disputeService,
                             ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer,
                             RevenueSplitService revenueSplitService,
                             SecurityProperties securityProperties,
                             StagingProperties stagingProperties,
                             InventoryService inventoryService,
                             OrderPaymentService orderPaymentService,
                             SettlementConfidenceService confidenceService,
                             GravitySettlementHelper gravityHelper,
                             DeviceValidationService deviceValidationService,
                             MerchantSkuPricingService skuPricingService,
                             UserValidationService userValidationService,
                             VideoArchiveService videoArchiveService,
                             SkuVisionEnrollmentService skuVisionEnrollmentService,
                             CouponService couponService,
                             MemberService memberService,
                             RefundPolicyService refundPolicyService,
                             NotificationService notificationService,
                             DeviceSlotMapper slotRepository,
                             ConsumerPreauthService consumerPreauthService,
                             SystemConfigService systemConfigService,
                             DistributedLockService distributedLockService,
                             @Lazy SettlementService self,
                             DisplaySnapshotHelper displaySnapshotHelper,
                             OrderViewAssembler orderViewAssembler) {
        this.sessionRepository = sessionRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.visionClient = visionClient;
        this.disputeService = disputeService;
        this.visionRecognitionProducer = visionRecognitionProducer;
        this.revenueSplitService = revenueSplitService;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
        this.inventoryService = inventoryService;
        this.orderPaymentService = orderPaymentService;
        this.confidenceService = confidenceService;
        this.gravityHelper = gravityHelper;
        this.deviceValidationService = deviceValidationService;
        this.skuPricingService = skuPricingService;
        this.userValidationService = userValidationService;
        this.videoArchiveService = videoArchiveService;
        this.skuVisionEnrollmentService = skuVisionEnrollmentService;
        this.couponService = couponService;
        this.memberService = memberService;
        this.refundPolicyService = refundPolicyService;
        this.notificationService = notificationService;
        this.slotRepository = slotRepository;
        this.consumerPreauthService = consumerPreauthService;
        this.systemConfigService = systemConfigService;
        this.distributedLockService = distributedLockService;
        this.self = self;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.orderViewAssembler = orderViewAssembler;
    }

    /** 人工审核后确认清单：无订单则首次扣款；有订单则按差额退/补。 */
    public record ConfirmDisputeResult(
            OrderReadModel order,
            int originalAmountCents,
            int finalAmountCents,
            int adjustmentCents
    ) {}

    /**
     * 视觉识别 HTTP 在事务外；分布式锁内先识别再短事务落库/扣款，避免占用 DB 连接等待 vision。
     */
    public OrderReadModel settle(ShoppingSession session) {
        return runWithSessionSettleLock(session.getSessionId(), () -> {
            if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
                return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
            }
            deviceValidationService.ensureSettlementAllowed(session.getDeviceId());

            VisionServiceClient.RecognitionResult recognition;
            try {
                recognition = visionClient.recognize(session);
                recognition = withGravityFallback(session, recognition);
            } catch (RestClientException | IllegalStateException e) {
                VisionServiceClient.RecognitionResult unavailable = new VisionServiceClient.RecognitionResult(
                        "UNAVAILABLE-" + session.getSessionId(), List.of(), 0f, true,
                        "vision-unavailable", List.of());
                log.warn("vision unavailable session={}", session.getSessionId(), e);
                // 经代理进入短事务写争议单，并抛出 DisputeRequiredException
                return self.escalateVisionUnavailable(session, unavailable);
            }
            return self.processRecognitionAfterVision(session, recognition);
        });
    }

    /** 识别已完成：短事务内 forUpdate + 落单/扣款。 */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionAfterVision(ShoppingSession session,
                                                        VisionServiceClient.RecognitionResult recognition) {
        sessionRepository.findByIdForUpdate(session.getSessionId());
        return processRecognitionResultUnlocked(session, recognition);
    }

    /**
     * 识别不可用：短事务建争议单后抛 {@link DisputeRequiredException}（方法签名供调用方 return）。
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class})
    public OrderReadModel escalateVisionUnavailable(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult unavailable) {
        escalateToDispute(session, unavailable, "识别服务暂时不可用，已转人工审核，本次暂未扣款");
        throw new IllegalStateException("unreachable after dispute escalate");
    }

    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        return self.processRecognitionResult(session, recognition, true);
    }

    /**
     * 主流开门柜：高置信度自动扣款；存疑或未识别到 SKU 一律进人工申诉，先不扣款。
     *
     * @param allowDevFallback 为 false 时不注入 mock SKU（运营识别测试）
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition,
                                             boolean allowDevFallback) {
        return runWithSessionSettleLock(session.getSessionId(), () -> {
            sessionRepository.findByIdForUpdate(session.getSessionId());
            return processRecognitionResultUnlocked(session, recognition, allowDevFallback);
        });
    }

    private OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                      VisionServiceClient.RecognitionResult recognition) {
        return processRecognitionResultUnlocked(session, recognition, true);
    }

    private OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                      VisionServiceClient.RecognitionResult recognition,
                                                      boolean allowDevFallback) {
        var existingOrder = orderRepository.findBySessionId(session.getSessionId());
        if (existingOrder.isPresent()) {
            return toDto(existingOrder.get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        recognition = withGravityFallback(session, recognition);
        recognition = forceReviewIfMockOrMismatch(recognition);
        if (recognition == null) {
            throw new IllegalStateException("recognition result is null after gravity/review normalize");
        }

        OrderReadModel early = trySettleWhenReviewRequired(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }
        early = trySettleDevMock(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }
        early = trySettleEmptyRecognition(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }

        String confidenceReason = confidenceService.reviewReasonIfNeeded(recognition);
        if (confidenceReason != null) {
            OrderReadModel stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
            if (systemConfigService.getBoolean(SystemConfigService.DISPUTE_AUTO_OPEN, true)) {
                escalateToDispute(session, recognition, confidenceReason);
            } else {
                log.info("dispute.auto_open=false, settle despite low confidence session={} reason={}",
                        session.getSessionId(), confidenceReason);
            }
        }

        var whitelistReason = skuVisionEnrollmentService.validateSettlementItems(
                session.getDeviceId(), recognition.items());
        if (whitelistReason.isPresent()) {
            escalateToDispute(session, recognition, whitelistReason.get());
        }

        return finalizeOrder(session, recognition.items());
    }

    /**
     * 视觉显式 need_review：沙箱 gravity-fill / 本地 mock 重力证据可静默结算，否则进审单。
     * @return 已结算订单，或 null 表示未拦截（继续后续路径）
     */
    private OrderReadModel trySettleWhenReviewRequired(ShoppingSession session,
                                                 VisionServiceClient.RecognitionResult recognition,
                                                 boolean allowDevFallback) {
        if (!recognition.needReview()) {
            return null;
        }
        // 沙箱：gravity-fill（视觉空+重力有货）允许按重力结算；错配仍禁止静默扣款
        if (!blocksSilentSettle(recognition) || allowsSandboxGravityFillSettle(recognition)) {
            OrderReadModel stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
        }
        // 本地 mock：有重力扣减证据时按购物车结算（OBS-012）；纯 mock 无证据仍进审单
        if (allowDevFallback) {
            OrderReadModel cartOrder = tryDevMockEvidenceSettle(session, recognition);
            if (cartOrder != null) {
                return cartOrder;
            }
        }
        escalateToDispute(session, recognition, humanReviewReason(recognition));
        return null;
    }

    /**
     * 本地 mock 自动结算路径：标称结果不可当作生产精度，有重力证据或沙箱 gravity-fill 除外。
     */
    private OrderReadModel trySettleDevMock(ShoppingSession session,
                                      VisionServiceClient.RecognitionResult recognition,
                                      boolean allowDevFallback) {
        if (!allowDevFallback || !securityProperties.mockEnabled()) {
            return null;
        }
        if (blocksSilentSettle(recognition)) {
            if (allowsSandboxGravityFillSettle(recognition)) {
                OrderReadModel stagingOrder = tryStagingGravitySettle(session);
                if (stagingOrder != null) {
                    return stagingOrder;
                }
            }
            OrderReadModel cartOrder = tryDevMockEvidenceSettle(session, recognition);
            if (cartOrder != null) {
                return cartOrder;
            }
            escalateToDispute(session, recognition, humanReviewReason(recognition));
            return null;
        }
        List<VisionServiceClient.RecognizedItem> cartItems = List.of();
        if (allowsGravityEvidenceSettle()) {
            cartItems = gravityHelper.toRecognizedItems(session.getGravityDeltas());
        }
        if (cartItems.isEmpty() && recognition.items() != null && !recognition.items().isEmpty()) {
            cartItems = recognition.items();
            log.info("dev mock settle session={} using vision items={}", session.getSessionId(), cartItems.size());
        } else {
            log.info("dev mock settle session={} cartItems={}", session.getSessionId(), cartItems.size());
        }
        return finalizeOrder(session, cartItems);
    }

    /** 视觉空结果：重力零结 / mock 零结 / 进审单。 */
    private OrderReadModel trySettleEmptyRecognition(ShoppingSession session,
                                               VisionServiceClient.RecognitionResult recognition,
                                               boolean allowDevFallback) {
        if (!recognition.items().isEmpty()) {
            return null;
        }
        OrderReadModel stagingOrder = tryStagingGravitySettle(session);
        if (stagingOrder != null) {
            return stagingOrder;
        }
        // 竞品口径：视觉空 + 重力净零（含显式 []）→ 未取货/拿了又放回，自动零结；
        // 无重力证据时生产仍进争议（防摄像头静默失败）。
        if (shouldAutoCompleteEmptyCart(session)) {
            log.info("empty cart auto-complete session={} device={} gravityPresent={}",
                    session.getSessionId(), session.getDeviceId(),
                    session.getGravityDeltas() != null);
            return finalizeOrder(session, List.of());
        }
        if (allowDevFallback && securityProperties.mockEnabled()) {
            log.warn("dev mock: empty recognition, zero-settle session={}", session.getSessionId());
            return finalizeOrder(session, List.of());
        }
        escalateToDispute(session, recognition, "未识别到商品，需人工审核");
        return null;
    }

    /**
     * 视觉无商品时是否自动零结。
     * <ul>
     *   <li>VISION_GRAVITY：重力净扣减为空且会话带重力字段（含 {@code []}）→ 传感器佐证未取货</li>
     *   <li>VISION / 无重力字段 → 看 empty_auto_complete_no_gravity 或 mock/staging，否则进审单</li>
     * </ul>
     */
    private boolean shouldAutoCompleteEmptyCart(ShoppingSession session) {
        if (systemConfigService.usesGravityFusion()) {
            List<VisionServiceClient.RecognizedItem> gravityItems =
                    gravityHelper.toRecognizedItems(session.getGravityDeltas());
            if (gravityItems != null && !gravityItems.isEmpty()) {
                return false;
            }
            if (session.getGravityDeltas() != null) {
                return true;
            }
        }
        if (systemConfigService.getBoolean(SystemConfigService.SETTLEMENT_EMPTY_AUTO_NO_GRAVITY, false)) {
            return true;
        }
        return securityProperties.mockEnabled()
                || stagingProperties.stagingMode()
                || stagingProperties.gravityFallbackSettle();
    }

    private boolean allowsGravityEvidenceSettle() {
        return systemConfigService.usesGravityFusion() || stagingProperties.gravityFallbackSettle();
    }

    /** 预发/沙箱 E2E：有重力扣减信号时优先按重力结算，避免无真实购物视频时误入争议。 */
    private OrderReadModel tryStagingGravitySettle(ShoppingSession session) {
        if (!allowsGravityEvidenceSettle()) {
            return null;
        }
        if (!stagingProperties.stagingMode() && !stagingProperties.gravityFallbackSettle()) {
            return null;
        }
        List<VisionServiceClient.RecognizedItem> gravityItems =
                gravityHelper.toRecognizedItems(session.getGravityDeltas());
        if (gravityItems.isEmpty()) {
            return null;
        }
        log.info("staging gravity settle session={} items={}", session.getSessionId(), gravityItems.size());
        return finalizeOrder(session, gravityItems);
    }

    /**
     * 沙箱重力兜底仅放行「视觉为空、重力补全」(gravity-fill)。
     * gravity-mismatch / 纯 mock 仍须人工审核。
     */
    private boolean allowsSandboxGravityFillSettle(VisionServiceClient.RecognitionResult recognition) {
        if (!systemConfigService.usesGravityFusion()) {
            return false;
        }
        if (!stagingProperties.stagingMode() && !stagingProperties.gravityFallbackSettle()) {
            return false;
        }
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        return version.contains(GRAVITY_FILL) && !version.contains(GRAVITY_MISMATCH);
    }

    /**
     * 本地演示：{@code AICABINET_MOCK_ENABLED} 下，只要会话有重力扣减证据，即按重力 finalize → PAID。
     * 覆盖 mock-v1 / gravity-fill / gravity-mismatch（演示柜模拟器常带重力，视觉 mock 易错配）。
     * 生产（mock 关闭）不走此分支；无重力证据返回 null，由调用方进审单。
     */
    private OrderReadModel tryDevMockEvidenceSettle(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        if (!allowsGravityEvidenceSettle()) {
            return null;
        }
        if (!securityProperties.mockEnabled()) {
            return null;
        }
        List<VisionServiceClient.RecognizedItem> cartItems =
                gravityHelper.toRecognizedItems(session.getGravityDeltas());
        if (cartItems == null || cartItems.isEmpty()) {
            return null;
        }
        log.info("dev mock settle with gravity evidence session={} cartItems={} version={}",
                session.getSessionId(), cartItems.size(), recognition.modelVersion());
        return finalizeOrder(session, cartItems);
    }

    private VisionServiceClient.RecognitionResult withGravityFallback(ShoppingSession session,
                                                                      VisionServiceClient.RecognitionResult recognition) {
        if (!systemConfigService.usesGravityFusion()) {
            return recognition;
        }
        return gravityHelper.reconcileWithGravity(session.getGravityDeltas(), recognition);
    }

    private String gravityDeltasForInventory(ShoppingSession session) {
        return systemConfigService.usesGravityFusion() ? session.getGravityDeltas() : null;
    }

    /** mock / gravity-mismatch / gravity-fill 不得静默按「生产精度」扣款。 */
    static boolean blocksSilentSettle(VisionServiceClient.RecognitionResult recognition) {
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        return version.contains("mock")
                || version.contains("fallback")
                || version.contains(GRAVITY_MISMATCH)
                || version.contains(GRAVITY_FILL);
    }

    private static VisionServiceClient.RecognitionResult forceReviewIfMockOrMismatch(
            VisionServiceClient.RecognitionResult recognition) {
        if (recognition == null || recognition.needReview() || !blocksSilentSettle(recognition)) {
            return recognition;
        }
        return new VisionServiceClient.RecognitionResult(
                recognition.taskId(),
                recognition.items(),
                recognition.overallConfidence(),
                true,
                recognition.modelVersion(),
                recognition.detectedClasses()
        );
    }

    static String reviewReasonFor(VisionServiceClient.RecognitionResult recognition) {
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        if (version.contains(GRAVITY_MISMATCH)) {
            return "视觉与重力数量不一致，需人工审核";
        }
        if (version.contains(GRAVITY_FILL)) {
            return "视觉为空，仅有重力信号（非生产识别精度），需人工审核";
        }
        if (version.contains("mock") || version.contains("fallback")) {
            return "模拟/兜底识别结果，非生产精度，需人工审核";
        }
        return "识别结果需人工审核";
    }

    /** 争议文案统一走 {@link SettlementDecision}，与矩阵单测同源。 */
    private static String humanReviewReason(VisionServiceClient.RecognitionResult recognition) {
        SettlementDecision decision = SettlementDecision.classify(recognition);
        String reason = decision.reviewReason(recognition);
        if (reason != null) {
            return reason;
        }
        return reviewReasonFor(recognition);
    }

    private void escalateToDispute(ShoppingSession session,
                                 VisionServiceClient.RecognitionResult recognition,
                                 String reason) {
        // 转人工暂不扣款：释放开门预授权，避免多笔争议会话叠冻结导致可用余额为 0（BUG-001）
        consumerPreauthService.releaseIfFrozen(session);
        disputeService.createTicket(session, recognition, reason);
        throw new DisputeRequiredException(reason);
    }

    public void submitAsyncRecognition(ShoppingSession session) {
        VisionRecognitionProducer producer = visionRecognitionProducer.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("vision async not enabled");
        }
        String taskId = "T-" + session.getSessionId();
        self.persistRecognitionTaskId(session.getSessionId(), taskId);
        session.setRecognitionTaskId(taskId);
        producer.publish(session.getSessionId(), session.getVideoUri(), taskId,
                session.getVideoClips(), session.getCameraFusionMode());
    }

    @Transactional
    public void persistRecognitionTaskId(String sessionId, String taskId) {
        ShoppingSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        session.setRecognitionTaskId(taskId);
        sessionRepository.save(session);
    }

    @Transactional
    public OrderReadModel settleManual(ShoppingSession session,
                                 List<VisionServiceClient.RecognizedItem> items) {
        return runWithSessionSettleLock(session.getSessionId(), () -> {
            sessionRepository.findByIdForUpdate(session.getSessionId());
            if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
                return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
            }
            return finalizeOrder(session, items);
        });
    }

    /**
     * 争议确认清单。无外层长事务包裹支付渠道：
     * 首次落单仍同事务 {@code chargeOrder}；改单为库存短事务 → 支付差额 → 状态短事务。
     */
    public ConfirmDisputeResult confirmDisputedItems(ShoppingSession session,
                                                   List<VisionServiceClient.RecognizedItem> items) {
        return runWithSessionSettleLock(session.getSessionId(), () -> {
            ConfirmDisputePrep prep = self.prepareConfirmDispute(session.getSessionId(), items);
            if (prep.firstChargeDone()) {
                return prep.result();
            }
            if (prep.paymentDelta() != 0) {
                orderPaymentService.applyPaymentDelta(prep.order(), prep.paymentDelta());
            }
            return self.finalizeConfirmDispute(prep);
        });
    }

    public record ConfirmDisputePrep(
            boolean firstChargeDone,
            ConfirmDisputeResult result,
            CabinetOrder order,
            int originalPayable,
            int finalTotal,
            int paymentDelta) {
        static ConfirmDisputePrep firstCharge(ConfirmDisputeResult result) {
            return new ConfirmDisputePrep(true, result, null, 0, 0, 0);
        }

        static ConfirmDisputePrep adjust(CabinetOrder order, int originalPayable, int finalTotal, int paymentDelta) {
            return new ConfirmDisputePrep(false, null, order, originalPayable, finalTotal, paymentDelta);
        }
    }

    @Transactional(noRollbackFor = {BalanceInsufficientException.class})
    public ConfirmDisputePrep prepareConfirmDispute(String sessionId,
                                                    List<VisionServiceClient.RecognizedItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_ITEMS_REQUIRED);
        }
        sessionRepository.findByIdForUpdate(sessionId);
        ShoppingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));

        var existing = orderRepository.findBySessionId(sessionId);
        if (existing.isEmpty()) {
            OrderReadModel order = finalizeOrder(session, items);
            int amount = order.totalAmountCents();
            return ConfirmDisputePrep.firstCharge(new ConfirmDisputeResult(order, 0, amount, amount));
        }

        CabinetOrder order = existing.get();
        hydrateOrderLines(order);
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_REFUNDED);
        }

        List<VisionServiceClient.RecognizedItem> oldItems = order.getLines().stream()
                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(),
                        l.getConfidence() != null ? l.getConfidence() : 1f))
                .toList();
        var batchBySku = order.getLines().stream()
                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        com.aicabinet.trade.domain.CabinetOrderLine::getSkuId,
                        com.aicabinet.trade.domain.CabinetOrderLine::getBatchNo,
                        (a, b) -> a));

        int originalPayable = order.getTotalAmountCents();
        applyItemsToOrder(order, items);
        recalculatePayableAfterLineChange(order);
        int finalTotal = order.getTotalAmountCents();
        int delta = finalTotal - originalPayable;

        // 先校验余额再动库存，避免 412 触发 UnexpectedRollbackException（BUG-007）
        if (delta > 0 && !userValidationService.canChargeViaPasswordFree(
                session.getUserId(), session.getEntryChannel())) {
            userValidationService.validateSufficientBalanceForCharge(session.getUserId(), delta);
        }

        if (order.isInventoryDeducted()) {
            var adjustedBatches = inventoryService.adjustForOrder(
                    session.getDeviceId(), oldItems, items, batchBySku, order.getOrderId());
            applyBatchNos(order, adjustedBatches);
        } else {
            var deductedBatches = inventoryService.deductForOrder(
                    session.getDeviceId(), items, order.getOrderId(), gravityDeltasForInventory(session));
            applyBatchNos(order, deductedBatches);
            order.setInventoryDeducted(true);
        }
        orderRepository.save(order);
        replaceOrderLines(order);
        log.info("dispute confirm prepare session={} order={} original={} final={} delta={}",
                sessionId, order.getOrderId(), originalPayable, finalTotal, delta);
        return ConfirmDisputePrep.adjust(order, originalPayable, finalTotal, delta);
    }

    @Transactional
    public ConfirmDisputeResult finalizeConfirmDispute(ConfirmDisputePrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        hydrateOrderLines(order);
        // 三端一致：确认/改单后退出争议态（DisputeService 结案兜底也会对齐）
        if ("DISPUTED".equals(order.getStatus())) {
            order.setStatus("PAID");
        }
        if (prep.paymentDelta() != 0) {
            revenueSplitService.adjustSplitAfterOrderChange(order, prep.originalPayable());
        }
        orderRepository.save(order);
        log.info("dispute confirm finalize order={} original={} final={} delta={}",
                order.getOrderId(), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
        return new ConfirmDisputeResult(toDto(order), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
    }

    /** 免单：退还该会话已扣款项（原路退回）；默认回库（兼容历史免单=误识别）。 */
    public int waiveAndRefund(ShoppingSession session) {
        return self.waiveAndRefund(session, true);
    }

    /**
     * 免单/全额退款。无外层长事务：库存短事务 → 退款（可含渠道 HTTP）→ 状态短事务。
     *
     * @param restoreInventory true=退货退款回库；false=仅退款不回库（货已离柜）
     */
    public int waiveAndRefund(ShoppingSession session, boolean restoreInventory) {
        return runWithSessionSettleLock(session.getSessionId(), () -> {
            WaiveRefundPrep prep = self.prepareWaiveRefund(session.getSessionId(), restoreInventory);
            if (prep == null) {
                return 0;
            }
            if (prep.refundCents() > 0) {
                orderPaymentService.refundOrder(prep.order(), prep.refundCents(), prep.reason());
            }
            return self.finalizeWaiveRefund(prep);
        });
    }

    public record WaiveRefundPrep(CabinetOrder order, int refundCents, String reason, boolean restoreInventory) {}

    @Transactional
    public WaiveRefundPrep prepareWaiveRefund(String sessionId, boolean restoreInventory) {
        sessionRepository.findByIdForUpdate(sessionId);
        ShoppingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        return orderRepository.findBySessionId(sessionId)
                .map(order -> buildWaiveRefundPrep(session, order, restoreInventory))
                .orElse(null);
    }

    private WaiveRefundPrep buildWaiveRefundPrep(ShoppingSession session, CabinetOrder order,
                                                 boolean restoreInventory) {
        hydrateOrderLines(order);
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            return null;
        }
        int amount = Math.max(0, orderPaymentService.netCompletedCents(order.getOrderId()));
        boolean didRestore = applyWaiveInventoryPolicy(order, restoreInventory);
        String reason = restoreInventory ? "争议免单退款(回库)" : "争议免单退款(不回库)";
        log.info("waive prepare session={} order={} refund={} restoreInventory={} didRestore={}",
                session.getSessionId(), order.getOrderId(), amount, restoreInventory, didRestore);
        return new WaiveRefundPrep(order, amount, reason, restoreInventory);
    }

    @Transactional
    public int finalizeWaiveRefund(WaiveRefundPrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElse(prep.order());
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            return prep.refundCents();
        }
        if (order.getRefundedAt() == null) {
            order.setRefundedAt(java.time.Instant.now());
        }
        // 支付层可能已累加 refundedCents；此处兜底保证免单后金额可见
        if (prep.refundCents() > 0 && order.getRefundedCents() < prep.refundCents()) {
            order.setRefundedCents(prep.refundCents());
        }
        order.setStatus(CabinetConstants.ORDER_STATUS_REFUNDED);
        orderRepository.save(order);
        revenueSplitService.voidSplitOnFullRefund(order.getOrderId());
        log.info("争议免单退款完成 order={} refund={} channel={} restoreInventory={}",
                order.getOrderId(), prep.refundCents(), order.getPayChannel(), prep.restoreInventory());
        return prep.refundCents();
    }

    private boolean applyWaiveInventoryPolicy(CabinetOrder order, boolean restoreInventory) {
        if (!order.isInventoryDeducted()) {
            return false;
        }
        List<VisionServiceClient.RecognizedItem> items = order.getLines().stream()
                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(), 1f))
                .toList();
        var batchBySku = order.getLines().stream()
                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        com.aicabinet.trade.domain.CabinetOrderLine::getSkuId,
                        com.aicabinet.trade.domain.CabinetOrderLine::getBatchNo,
                        (a, b) -> a));
        if (restoreInventory) {
            inventoryService.restoreForOrder(order.getDeviceId(), items, batchBySku);
            order.setInventoryDeducted(false);
            return true;
        }
        inventoryService.recordRefundKeptGoods(
                order.getDeviceId(), items, batchBySku, order.getOrderId());
        return false;
    }

    /**
     * 按行部分退款（竞品口径）：指定 SKU/数量退款；行级或默认决定是否回库。
     * 退完全部行 → {@code REFUNDED}；否则 → {@code PARTIAL_REFUNDED}。
     * 无外层长事务：库存/行改短事务 → 退款（可含渠道）→ 状态短事务。
     */
    public PartialRefundResult partialRefund(CabinetOrder order,
                                             List<OrderRefundRequest.PartialRefundLine> refundLines,
                                             boolean defaultRestore,
                                             String reason) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定要退款的商品行");
        }
        PartialRefundPrep prep = self.preparePartialRefund(order.getOrderId(), refundLines, defaultRestore);
        orderPaymentService.refundOrder(prep.order(), prep.refundCents(),
                reason == null ? "按行部分退款" : reason);
        return self.finalizePartialRefund(prep);
    }

    public record PartialRefundPrep(
            CabinetOrder order,
            int refundCents,
            int priorRefunded,
            boolean remainingEmpty,
            boolean anyRestored) {}

    @Transactional
    public PartialRefundPrep preparePartialRefund(String orderId,
                                                  List<OrderRefundRequest.PartialRefundLine> refundLines,
                                                  boolean defaultRestore) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        hydrateOrderLines(order);
        if (order.getLines() == null || order.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单无商品行，无法按行退款");
        }
        int priorPayable = Math.max(0, order.getTotalAmountCents());
        PartialRefundPartition partition = validateAndPartitionRefundLines(order, refundLines, defaultRestore);

        List<CabinetOrderLine> remaining = buildRemainingLines(
                mergeLinesBySku(order.getLines()), partition.refundQtyBySku());
        order.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        couponService.recalcOrRestoreAfterPartialRefund(order, newSubtotal);
        recalculatePayableAfterLineChange(order);
        int refundCents = Math.max(0, priorPayable - order.getTotalAmountCents());
        if (refundCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额为 0");
        }

        applyPartialRefundInventory(order, partition, remaining.isEmpty());
        int priorRefunded = Math.max(0, order.getRefundedCents());
        orderRepository.save(order);
        replaceOrderLines(order);
        return new PartialRefundPrep(order, refundCents, priorRefunded, remaining.isEmpty(), partition.anyRestored());
    }

    @Transactional
    public PartialRefundResult finalizePartialRefund(PartialRefundPrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        hydrateOrderLines(order);
        int refundCents = prep.refundCents();
        // 支付层正常会累加 refundedCents；演示账号早退 / 历史路径漏写时在此兜底
        if (order.getRefundedCents() < prep.priorRefunded() + refundCents) {
            order.setRefundedCents(prep.priorRefunded() + refundCents);
        }
        if (order.getRefundedAt() == null) {
            order.setRefundedAt(java.time.Instant.now());
        }
        boolean full = prep.remainingEmpty() || order.getTotalAmountCents() <= 0;
        order.setStatus(full ? CabinetConstants.ORDER_STATUS_REFUNDED : "PARTIAL_REFUNDED");
        if (full) {
            order.setRefundedAt(java.time.Instant.now());
        }
        revenueSplitService.adjustSplitAfterPartialRefund(order, full);
        orderRepository.save(order);
        replaceOrderLines(order);
        log.info("partial refund order={} refundCents={} status={} anyRestored={}",
                order.getOrderId(), refundCents, order.getStatus(), prep.anyRestored());
        return new PartialRefundResult(refundCents, order.getStatus(), prep.anyRestored());
    }

    private record PartialRefundPartition(
            List<VisionServiceClient.RecognizedItem> restoreItems,
            List<VisionServiceClient.RecognizedItem> keptItems,
            Map<String, String> batchBySku,
            Map<String, Integer> refundQtyBySku,
            boolean anyRestored) {
    }

    private PartialRefundPartition validateAndPartitionRefundLines(
            CabinetOrder order,
            List<OrderRefundRequest.PartialRefundLine> refundLines,
            boolean defaultRestore) {
        Map<String, CabinetOrderLine> bySku = mergeLinesBySku(order.getLines());
        Map<String, Integer> refundQtyBySku = validatePartialRefundQuantities(bySku, refundLines);
        return buildPartialRefundPartition(bySku, refundLines, defaultRestore, refundQtyBySku);
    }

    private static PartialRefundPartition buildPartialRefundPartition(
            Map<String, CabinetOrderLine> bySku,
            List<OrderRefundRequest.PartialRefundLine> refundLines,
            boolean defaultRestore,
            Map<String, Integer> refundQtyBySku) {
        List<VisionServiceClient.RecognizedItem> restoreItems = new java.util.ArrayList<>();
        List<VisionServiceClient.RecognizedItem> keptItems = new java.util.ArrayList<>();
        Map<String, String> batchBySku = new java.util.HashMap<>();
        boolean anyRestored = false;
        for (OrderRefundRequest.PartialRefundLine req : refundLines) {
            if (req == null || req.skuId() == null || req.skuId().isBlank()) {
                continue;
            }
            String sku = req.skuId().trim();
            CabinetOrderLine line = bySku.get(sku);
            boolean restore = req.restoreInventory() != null ? req.restoreInventory() : defaultRestore;
            if (line.getBatchNo() != null && !line.getBatchNo().isBlank()) {
                batchBySku.putIfAbsent(sku, line.getBatchNo());
            }
            var item = new VisionServiceClient.RecognizedItem(sku, req.quantity(), 1f);
            if (restore) {
                restoreItems.add(item);
                anyRestored = true;
            } else {
                keptItems.add(item);
            }
        }
        return new PartialRefundPartition(restoreItems, keptItems, batchBySku, refundQtyBySku, anyRestored);
    }

    private void applyPartialRefundInventory(CabinetOrder order, PartialRefundPartition partition,
                                             boolean remainingEmpty) {
        if (!order.isInventoryDeducted()) {
            return;
        }
        if (!partition.restoreItems().isEmpty()) {
            inventoryService.restoreForOrder(order.getDeviceId(), partition.restoreItems(), partition.batchBySku());
        }
        if (!partition.keptItems().isEmpty()) {
            inventoryService.recordRefundKeptGoods(
                    order.getDeviceId(), partition.keptItems(), partition.batchBySku(), order.getOrderId());
        }
        if (remainingEmpty) {
            order.setInventoryDeducted(false);
        }
    }

    public record PartialRefundResult(int refundedCents, String status, boolean anyInventoryRestored) {}

    @Transactional(readOnly = true)
    public int estimatePartialRefundCents(CabinetOrder order,
                                          List<OrderRefundRequest.PartialRefundLine> refundLines) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            return 0;
        }
        CabinetOrder scratch = copyOrderForPartialRefundEstimate(order);
        hydrateOrderLines(scratch);
        if (scratch.getLines() == null || scratch.getLines().isEmpty()) {
            return 0;
        }
        Map<String, CabinetOrderLine> bySku = mergeLinesBySku(scratch.getLines());
        Map<String, Integer> refundQtyBySku = validatePartialRefundQuantities(bySku, refundLines);
        int priorPayable = Math.max(0, scratch.getTotalAmountCents());
        List<CabinetOrderLine> remaining = buildRemainingLines(bySku, refundQtyBySku);
        scratch.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        applyPartialRefundPayablePreview(scratch, newSubtotal);
        recalculatePayableAfterLineChange(scratch);
        return Math.max(0, priorPayable - scratch.getTotalAmountCents());
    }

    private static CabinetOrder copyOrderForPartialRefundEstimate(CabinetOrder order) {
        CabinetOrder copy = new CabinetOrder();
        copy.setOrderId(order.getOrderId());
        copy.setTotalAmountCents(order.getTotalAmountCents());
        copy.setOriginalAmountCents(order.getOriginalAmountCents());
        copy.setCouponId(order.getCouponId());
        copy.setCouponDiscountCents(order.getCouponDiscountCents());
        copy.setMemberDiscountCents(order.getMemberDiscountCents());
        if (order.getLines() != null) {
            List<CabinetOrderLine> lines = new java.util.ArrayList<>();
            for (CabinetOrderLine line : order.getLines()) {
                CabinetOrderLine l = new CabinetOrderLine();
                l.setSkuId(line.getSkuId());
                l.setSkuName(line.getSkuName());
                l.setQuantity(line.getQuantity());
                l.setUnitPriceCents(line.getUnitPriceCents());
                l.setLineAmountCents(line.getLineAmountCents());
                l.setBatchNo(line.getBatchNo());
                l.setSlotId(line.getSlotId());
                l.setUnitCostCents(line.getUnitCostCents());
                l.setConfidence(line.getConfidence());
                lines.add(l);
            }
            copy.setLines(lines);
        }
        return copy;
    }

    /** 只读预估：按剩余明细重算应付，不写券状态。 */
    private void applyPartialRefundPayablePreview(CabinetOrder order, int remainingSubtotalCents) {
        order.setOriginalAmountCents(Math.max(0, remainingSubtotalCents));
        if (order.getCouponId() == null) {
            order.setCouponDiscountCents(0);
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        int discount = couponService.discountForOrderCoupon(order.getCouponId(), remainingSubtotalCents);
        if (discount <= 0) {
            order.setCouponId(null);
            order.setCouponDiscountCents(0);
            order.setTotalAmountCents(Math.max(0, remainingSubtotalCents));
            return;
        }
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, remainingSubtotalCents - discount));
    }

    private static Map<String, CabinetOrderLine> mergeLinesBySku(List<CabinetOrderLine> lines) {
        Map<String, CabinetOrderLine> bySku = new java.util.LinkedHashMap<>();
        for (CabinetOrderLine line : lines) {
            if (line.getSkuId() == null) {
                continue;
            }
            bySku.merge(line.getSkuId(), line, (a, b) -> {
                a.setQuantity(a.getQuantity() + b.getQuantity());
                a.setLineAmountCents(a.getLineAmountCents() + b.getLineAmountCents());
                return a;
            });
        }
        return bySku;
    }

    private static Map<String, Integer> validatePartialRefundQuantities(
            Map<String, CabinetOrderLine> bySku,
            List<OrderRefundRequest.PartialRefundLine> refundLines) {
        Map<String, Integer> refundQtyBySku = new java.util.LinkedHashMap<>();
        for (OrderRefundRequest.PartialRefundLine req : refundLines) {
            if (req == null || req.skuId() == null || req.skuId().isBlank() || req.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款行 SKU/数量无效");
            }
            String sku = req.skuId().trim();
            CabinetOrderLine line = bySku.get(sku);
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单不含商品：" + sku);
            }
            int already = refundQtyBySku.getOrDefault(sku, 0);
            int need = already + req.quantity();
            if (need > line.getQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "退款数量超过订单行：" + sku + " 可退 " + (line.getQuantity() - already));
            }
            refundQtyBySku.put(sku, need);
        }
        return refundQtyBySku;
    }

    private static List<CabinetOrderLine> buildRemainingLines(
            Map<String, CabinetOrderLine> bySku,
            Map<String, Integer> refundQtyBySku) {
        List<CabinetOrderLine> remaining = new java.util.ArrayList<>();
        for (Map.Entry<String, CabinetOrderLine> e : bySku.entrySet()) {
            CabinetOrderLine src = e.getValue();
            int cut = refundQtyBySku.getOrDefault(e.getKey(), 0);
            int left = src.getQuantity() - cut;
            if (left > 0) {
                CabinetOrderLine copy = new CabinetOrderLine();
                copy.setSkuId(src.getSkuId());
                copy.setSkuName(src.getSkuName());
                copy.setQuantity(left);
                copy.setUnitPriceCents(src.getUnitPriceCents());
                copy.setLineAmountCents(src.getUnitPriceCents() * left);
                copy.setConfidence(src.getConfidence());
                copy.setBatchNo(src.getBatchNo());
                copy.setSlotId(src.getSlotId());
                copy.setUnitCostCents(src.getUnitCostCents());
                remaining.add(copy);
            }
        }
        return remaining;
    }

    private OrderReadModel finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());
        CabinetOrder order = buildOrder(session, items);
        CouponService.BestCoupon appliedCoupon = selectBestCouponForOrder(order);
        boolean unpaid = detectUnpaidBeforeCharge(session, order);
        if (unpaid) {
            appliedCoupon = null;
            clearCouponSelection(order);
        }
        var batchBySku = inventoryService.deductForOrder(
                session.getDeviceId(), items, order.getOrderId(), gravityDeltasForInventory(session));
        applyBatchNos(order, batchBySku);
        order.setInventoryDeducted(true);
        order.setStatus(unpaid ? "PENDING" : "PAID");
        // Persist order before charge/coupon mark — payment_operation & user_coupon FK to cabinet_order
        orderRepository.save(order);
        persistOrderLines(order);

        if (unpaid) {
            return finishUnpaidOrder(session, order);
        }
        if (!tryChargeSettledOrder(session, order)) {
            return toDto(order);
        }
        orderRepository.save(order);
        if (appliedCoupon != null) {
            couponService.markUsed(
                    order.getUserId(),
                    appliedCoupon.couponId(),
                    order.getOrderId(),
                    order.getDeviceId(),
                    order.getCouponDiscountCents()
            );
        }
        revenueSplitService.recordSplit(order);
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        try {
            memberService.onOrderPaid(session.getUserId(), order.getTotalAmountCents(), order.getOrderId());
        } catch (Exception e) {
            log.warn("member stats update failed order={}", order.getOrderId(), e);
        }
        try {
            notificationService.notifyConsumer(
                    session.getUserId(),
                    "order_paid",
                    Map.of("orderId", order.getOrderId(), "amount", yuan(order.getTotalAmountCents())),
                    "ORDER",
                    order.getOrderId());
        } catch (Exception e) {
            log.warn("order paid notification failed order={}", order.getOrderId(), e);
        }
        videoArchiveService.archiveAfterSettlement(session);
        log.info("settled session={} order={} amount={} couponDiscount={} channel={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents(),
                order.getCouponDiscountCents(), order.getPayChannel());
        return toDto(order);
    }

    /** 余额不足时预判为待支付（优惠券延后核销）。 */
    private boolean detectUnpaidBeforeCharge(ShoppingSession session, CabinetOrder order) {
        if (userValidationService.canChargeViaPasswordFree(session.getUserId(), session.getEntryChannel())) {
            return false;
        }
        try {
            int hold;
            if (ConsumerPreauthService.STATUS_FROZEN.equalsIgnoreCase(
                    session.getPreauthStatus() == null ? "" : session.getPreauthStatus())) {
                hold = Math.max(0, session.getPreauthCents());
            } else {
                hold = 0;
            }
            userValidationService.validateSufficientBalanceForCharge(
                    session.getUserId(), order.getTotalAmountCents(), hold);
            return false;
        } catch (BalanceInsufficientException e) {
            return true;
        }
    }

    private OrderReadModel finishUnpaidOrder(ShoppingSession session, CabinetOrder order) {
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        videoArchiveService.archiveAfterSettlement(session);
        log.info("unpaid order created session={} order={} amount={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
        return toDto(order);
    }

    /**
     * 尝试扣款；余额不足时转 PENDING 并返回 false（订单已落库）。
     * 其他支付异常继续抛出。
     */
    private boolean tryChargeSettledOrder(ShoppingSession session, CabinetOrder order) {
        try {
            orderPaymentService.chargeOrder(order);
            return true;
        } catch (ResponseStatusException e) {
            if (!isInsufficientBalance(e)) {
                throw e;
            }
            // 扣款失败转待支付：同样不占券，补扣时再选
            clearCouponSelection(order);
            order.setStatus("PENDING");
            orderRepository.save(order);
            session.setOrderId(order.getOrderId());
            sessionRepository.save(session);
            videoArchiveService.archiveAfterSettlement(session);
            log.info("unpaid order after charge fail session={} order={} amount={}",
                    session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
            return false;
        }
    }

    private static String yuan(int cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private static boolean isInsufficientBalance(ResponseStatusException e) {
        if (e.getStatusCode() != HttpStatus.PRECONDITION_FAILED) {
            return false;
        }
        String reason = e.getReason();
        return reason != null && (reason.contains("余额") || reason.toLowerCase().contains("insufficient"));
    }

    /** Pick preferred or best coupon and rewrite payable; caller must markUsed after order is persisted (FK). */
    private CouponService.BestCoupon selectBestCouponForOrder(CabinetOrder order) {
        int subtotal = order.getTotalAmountCents();
        order.setOriginalAmountCents(subtotal);
        if (subtotal <= 0 || order.getUserId() == null) {
            return null;
        }
        Long preferred = null;
        if (order.getSessionId() != null) {
            preferred = sessionRepository.findById(order.getSessionId())
                    .map(ShoppingSession::getPreferredCouponId)
                    .orElse(null);
        }
        var best = couponService.selectPreferredOrBest(order.getUserId(), preferred, subtotal);
        if (best.isEmpty()) {
            return null;
        }
        var pick = best.get();
        int discount = Math.min(pick.discountCents(), subtotal);
        order.setCouponId(pick.couponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        log.info("applied coupon order={} couponId={} preferred={} discount={} payable={}",
                order.getOrderId(), pick.couponId(), preferred, discount, order.getTotalAmountCents());
        return pick;
    }

    /** 待支付不占券：还原应付为折前金额并清空券字段。 */
    private static void clearCouponSelection(CabinetOrder order) {
        int original = order.getOriginalAmountCents();
        if (original <= 0) {
            original = order.getTotalAmountCents() + Math.max(0, order.getCouponDiscountCents());
        }
        order.setCouponId(null);
        order.setCouponDiscountCents(0);
        order.setTotalAmountCents(Math.max(0, original));
        order.setOriginalAmountCents(Math.max(0, original));
    }

    private void applyItemsToOrder(CabinetOrder order, List<VisionServiceClient.RecognizedItem> items) {
        order.getLines().clear();
        Map<String, String> slotBySku = inferSlotBySku(order.getDeviceId());
        int total = 0;
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuCatalog sku = skuCatalogRepository.findById(item.skuId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            ApiMessages.SKU_NOT_FOUND + "：" + item.skuId()));
            int unitPrice = skuPricingService.resolveUnitPriceCents(order.getDeviceId(), sku);
            unitPrice = memberService.applyMemberPriceDiscount(order.getUserId(), unitPrice);
            int lineAmount = unitPrice * item.quantity();
            total += lineAmount;

            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId(sku.getSkuId());
            line.setSkuName(sku.getSkuName());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(unitPrice);
            line.setLineAmountCents(lineAmount);
            line.setUnitCostCents(sku.getPurchaseCostCents());
            line.setConfidence(item.confidence());
            line.setSlotId(slotBySku.get(sku.getSkuId()));
            order.addLine(line);
        }
        order.setOriginalAmountCents(total);
        order.setTotalAmountCents(total);
    }

    /** 争议改单后按新明细重算折后应付（保留已绑券/会员折扣字段）。 */
    private void recalculatePayableAfterLineChange(CabinetOrder order) {
        int subtotal = order.getLines().stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        order.setOriginalAmountCents(subtotal);
        int couponDisc = 0;
        if (order.getCouponId() != null && subtotal > 0) {
            couponDisc = couponService.discountForOrderCoupon(order.getCouponId(), subtotal);
        }
        order.setCouponDiscountCents(couponDisc);
        int memberDisc = Math.min(Math.max(0, order.getMemberDiscountCents()),
                Math.max(0, subtotal - couponDisc));
        order.setMemberDiscountCents(memberDisc);
        order.setTotalAmountCents(Math.max(0, subtotal - couponDisc - memberDisc));
    }

    /** SKU 唯一绑定某货道时回填货道；同一 SKU 出现在多个货道则不推断。 */
    private Map<String, String> inferSlotBySku(String deviceId) {
        Map<String, String> map = new java.util.HashMap<>();
        if (deviceId == null) {
            return map;
        }
        for (DeviceSlot slot : slotRepository.findByIdDeviceId(deviceId)) {
            if (!slot.isEnabled() || slot.getAssignedSkuId() == null || slot.getAssignedSkuId().isBlank()) {
                continue;
            }
            String existing = map.putIfAbsent(slot.getAssignedSkuId(), slot.getSlotCode());
            if (existing != null) {
                map.put(slot.getAssignedSkuId(), null);
            }
        }
        map.values().removeIf(v -> v == null);
        return map;
    }

    private static void applyBatchNos(CabinetOrder order, java.util.Map<String, String> batchBySku) {
        if (batchBySku == null || batchBySku.isEmpty()) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            String batch = batchBySku.get(line.getSkuId());
            if (batch != null && !batch.isBlank()) {
                line.setBatchNo(batch);
            }
        }
    }

    private CabinetOrder buildOrder(ShoppingSession session,
                                    List<VisionServiceClient.RecognizedItem> items) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(BizIds.nextNumeric());
        order.setSessionId(session.getSessionId());
        order.setUserId(session.getUserId());
        order.setDeviceId(session.getDeviceId());
        displaySnapshotHelper.stampOrderSnapshot(order);
        order.setStatus("PAID");
        applyItemsToOrder(order, items);
        return order;
    }

    @Transactional(readOnly = true)
    public OrderReadModel getOrderBySession(String sessionId) {
        return orderRepository.findBySessionId(sessionId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
    }

    private void persistOrderLines(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            line.setOrderId(order.getOrderId());
            orderLineRepository.save(line);
        }
    }

    private void replaceOrderLines(CabinetOrder order) {
        orderLineRepository.deleteByOrderId(order.getOrderId());
        if (order.getLines() == null) {
            return;
        }
        for (CabinetOrderLine line : order.getLines()) {
            line.setId(null);
            line.setOrderId(order.getOrderId());
            orderLineRepository.save(line);
        }
    }

    private void hydrateOrderLines(CabinetOrder order) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        if (order.getLines() != null && !order.getLines().isEmpty()) {
            return;
        }
        order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
    }

    private OrderReadModel toDto(CabinetOrder order) {
        hydrateOrderLines(order);
        String refundPolicy = refundPolicyService != null
                ? refundPolicyService.resolveForDevice(order.getDeviceId()).name()
                : null;
        String splitStatus = revenueSplitService.findStatusByOrderId(order.getOrderId()).orElse(null);
        return orderViewAssembler.assembleDetail(
                order,
                order.getLines(),
                splitStatus,
                resolvePaidAt(order),
                refundPolicy,
                null);
    }

    private Instant resolvePaidAt(CabinetOrder order) {
        String status = order.getStatus();
        if (status == null
                || "PENDING".equals(status)
                || "CANCELLED".equals(status)
                || CabinetConstants.ORDER_STATUS_FAILED.equals(status)) {
            return null;
        }
        String opId = order.getPaymentOperationId();
        if (opId != null && !opId.isBlank()) {
            Instant at = orderPaymentService.findOperationCreatedAt(opId).orElse(null);
            if (at != null) {
                return at;
            }
        }
        return order.getCreatedAt();
    }

    static String sessionSettleLockKey(String sessionId) {
        return "session:settle:" + sessionId;
    }

    private <T> T runWithSessionSettleLock(String sessionId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(sessionSettleLockKey(sessionId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话结算处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException | DisputeRequiredException | BalanceInsufficientException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(sessionSettleLockKey(sessionId));
        }
    }
}
