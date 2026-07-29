package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.messaging.VisionRecognitionProducer;
import com.aicabinet.trade.mapper.*;
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

import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {

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
    private final DemoDataService demoDataService;
    private final DeviceValidationService deviceValidationService;
    private final MerchantSkuPricingService skuPricingService;
    private final UserValidationService userValidationService;
    private final VideoArchiveService videoArchiveService;
    private final SkuVisionEnrollmentService skuVisionEnrollmentService;
    private final CouponService couponService;
    private final MemberService memberService;
    private final RefundPolicyService refundPolicyService;

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
                             DemoDataService demoDataService,
                             DeviceValidationService deviceValidationService,
                             MerchantSkuPricingService skuPricingService,
                             UserValidationService userValidationService,
                             VideoArchiveService videoArchiveService,
                             SkuVisionEnrollmentService skuVisionEnrollmentService,
                             CouponService couponService,
                             MemberService memberService,
                             RefundPolicyService refundPolicyService) {
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
        this.demoDataService = demoDataService;
        this.deviceValidationService = deviceValidationService;
        this.skuPricingService = skuPricingService;
        this.userValidationService = userValidationService;
        this.videoArchiveService = videoArchiveService;
        this.skuVisionEnrollmentService = skuVisionEnrollmentService;
        this.couponService = couponService;
        this.memberService = memberService;
        this.refundPolicyService = refundPolicyService;
    }

    /** 人工审核后确认清单：无订单则首次扣款；有订单则按差额退/补。 */
    public record ConfirmDisputeResult(
            OrderDto order,
            int originalAmountCents,
            int finalAmountCents,
            int adjustmentCents
    ) {}

    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderDto settle(ShoppingSession session) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());

        try {
            VisionServiceClient.RecognitionResult recognition = visionClient.recognize(session);
            recognition = withGravityFallback(session, recognition);
            return processRecognitionResult(session, recognition);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("vision unavailable, hold charge and escalate session={}", session.getSessionId(), e);
            VisionServiceClient.RecognitionResult unavailable = new VisionServiceClient.RecognitionResult(
                    "UNAVAILABLE-" + session.getSessionId(), List.of(), 0f, true,
                    "vision-unavailable", List.of());
            escalateToDispute(session, unavailable, "识别服务暂时不可用，已转人工审核，本次暂未扣款");
            throw e;
        }
    }

    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderDto processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        return processRecognitionResult(session, recognition, true);
    }

    /**
     * 主流开门柜：高置信度自动扣款；存疑或未识别到 SKU 一律进人工申诉，先不扣款。
     *
     * @param allowDevFallback 为 false 时不注入 mock SKU（运营识别测试）
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderDto processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition,
                                             boolean allowDevFallback) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        recognition = withGravityFallback(session, recognition);
        recognition = forceReviewIfMockOrMismatch(recognition);

        // Honor explicit vision need_review even in local mock mode (dispute E2E toggle).
        // Previously mock cart settle short-circuited before this check, so force-review never fired.
        if (recognition.needReview()) {
            // 沙箱：gravity-fill（视觉空+重力有货）允许按重力结算；错配 / 纯 mock 仍禁止静默扣款
            if (!blocksSilentSettle(recognition) || allowsSandboxGravityFillSettle(recognition)) {
                OrderDto stagingOrder = tryStagingGravitySettle(session);
                if (stagingOrder != null) {
                    return stagingOrder;
                }
            }
            // 本地 mock：仅「非错配/非 mock 标称」且有重力扣减时按演示结算；错配与 mock-v1 一律进审单
            if (allowDevFallback && securityProperties.mockEnabled() && !blocksSilentSettle(recognition)) {
                List<VisionServiceClient.RecognizedItem> cartItems =
                        gravityHelper.toRecognizedItems(session.getGravityDeltas());
                if (!cartItems.isEmpty()) {
                    log.info("dev mock settle on needReview session={} cartItems={}",
                            session.getSessionId(), cartItems.size());
                    return finalizeOrder(session, cartItems);
                }
            }
            escalateToDispute(session, recognition, reviewReasonFor(recognition));
        }

        if (allowDevFallback && securityProperties.mockEnabled()) {
            // mock 标称结果不可当作生产精度自动扣款；沙箱 gravity-fill 除外
            if (blocksSilentSettle(recognition)) {
                if (allowsSandboxGravityFillSettle(recognition)) {
                    OrderDto stagingOrder = tryStagingGravitySettle(session);
                    if (stagingOrder != null) {
                        return stagingOrder;
                    }
                }
                escalateToDispute(session, recognition, reviewReasonFor(recognition));
            }
            List<VisionServiceClient.RecognizedItem> cartItems =
                    gravityHelper.toRecognizedItems(session.getGravityDeltas());
            if (cartItems.isEmpty() && recognition.items() != null && !recognition.items().isEmpty()) {
                cartItems = recognition.items();
                log.info("dev mock settle session={} using vision items={}", session.getSessionId(), cartItems.size());
            } else {
                log.info("dev mock settle session={} cartItems={}", session.getSessionId(), cartItems.size());
            }
            return finalizeOrder(session, cartItems);
        }

        if (recognition.items().isEmpty()) {
            OrderDto stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
            if (allowDevFallback && securityProperties.mockEnabled()) {
                log.warn("dev mock: empty recognition, zero-settle session={}", session.getSessionId());
                return finalizeOrder(session, List.of());
            }
            escalateToDispute(session, recognition, "未识别到商品，需人工审核");
        }

        String confidenceReason = confidenceService.reviewReasonIfNeeded(recognition);
        if (confidenceReason != null) {
            OrderDto stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
            escalateToDispute(session, recognition, confidenceReason);
        }

        var whitelistReason = skuVisionEnrollmentService.validateSettlementItems(
                session.getDeviceId(), recognition.items());
        if (whitelistReason.isPresent()) {
            escalateToDispute(session, recognition, whitelistReason.get());
        }

        return finalizeOrder(session, recognition.items());
    }

    /** 预发/沙箱 E2E：有重力扣减信号时优先按重力结算，避免无真实购物视频时误入争议。 */
    private OrderDto tryStagingGravitySettle(ShoppingSession session) {
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
        if (!stagingProperties.stagingMode() && !stagingProperties.gravityFallbackSettle()) {
            return false;
        }
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        return version.contains("gravity-fill") && !version.contains("gravity-mismatch");
    }

    private VisionServiceClient.RecognitionResult withGravityFallback(ShoppingSession session,
                                                                      VisionServiceClient.RecognitionResult recognition) {
        return gravityHelper.reconcileWithGravity(session.getGravityDeltas(), recognition);
    }

    /** mock / gravity-mismatch / gravity-fill 不得静默按「生产精度」扣款。 */
    static boolean blocksSilentSettle(VisionServiceClient.RecognitionResult recognition) {
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        return version.contains("mock")
                || version.contains("fallback")
                || version.contains("gravity-mismatch")
                || version.contains("gravity-fill");
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
        if (version.contains("gravity-mismatch")) {
            return "视觉与重力数量不一致，需人工审核";
        }
        if (version.contains("gravity-fill")) {
            return "视觉为空，仅有重力信号（非生产识别精度），需人工审核";
        }
        if (version.contains("mock") || version.contains("fallback")) {
            return "模拟/兜底识别结果，非生产精度，需人工审核";
        }
        return "识别结果需人工审核";
    }

    private void escalateToDispute(ShoppingSession session,
                                 VisionServiceClient.RecognitionResult recognition,
                                 String reason) {
        disputeService.createTicket(session, recognition, reason);
        throw new DisputeRequiredException(reason);
    }

    private List<VisionServiceClient.RecognizedItem> devFallbackItems(String deviceId) {
        String skuId = demoDataService.resolveFallbackSku(deviceId);
        log.info("dev fallback SKU from inventory device={} sku={}", deviceId, skuId);
        return List.of(new VisionServiceClient.RecognizedItem(skuId, 1, 0.92f));
    }

    public void submitAsyncRecognition(ShoppingSession session) {
        VisionRecognitionProducer producer = visionRecognitionProducer.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("vision async not enabled");
        }
        String taskId = "T-" + session.getSessionId();
        session.setRecognitionTaskId(taskId);
        sessionRepository.save(session);
        producer.publish(session.getSessionId(), session.getVideoUri(), taskId,
                session.getVideoClips(), session.getCameraFusionMode());
    }

    @Transactional
    public OrderDto settleManual(ShoppingSession session,
                                 List<VisionServiceClient.RecognizedItem> items) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }
        return finalizeOrder(session, items);
    }

    @Transactional
    public ConfirmDisputeResult confirmDisputedItems(ShoppingSession session,
                                                   List<VisionServiceClient.RecognizedItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_ITEMS_REQUIRED);
        }

        var existing = orderRepository.findBySessionId(session.getSessionId());
        if (existing.isEmpty()) {
            OrderDto order = finalizeOrder(session, items);
            int amount = order.totalAmountCents();
            return new ConfirmDisputeResult(order, 0, amount, amount);
        }

        CabinetOrder order = existing.get();
        hydrateOrderLines(order);
        if ("REFUNDED".equals(order.getStatus())) {
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

        int original = order.getTotalAmountCents();
        applyItemsToOrder(order, items);
        int finalTotal = order.getTotalAmountCents();
        int delta = finalTotal - original;

        if (order.isInventoryDeducted()) {
            inventoryService.adjustForOrder(session.getDeviceId(), oldItems, items, batchBySku);
        } else {
            var deductedBatches = inventoryService.deductForOrder(
                    session.getDeviceId(), items, session.getSessionId(), session.getGravityDeltas());
            applyBatchNos(order, deductedBatches);
            order.setInventoryDeducted(true);
        }
        orderPaymentService.applyPaymentDelta(order, delta);
        if ("DISPUTED".equals(order.getStatus())) {
            order.setStatus("PAID");
        }
        orderRepository.save(order);
        replaceOrderLines(order);
        log.info("dispute adjust session={} order={} original={} final={} delta={}",
                session.getSessionId(), order.getOrderId(), original, finalTotal, delta);
        return new ConfirmDisputeResult(toDto(order), original, finalTotal, delta);
    }

    /** 免单：退还该会话已扣款项（原路退回）。 */
    @Transactional
    public int waiveAndRefund(ShoppingSession session) {
        return orderRepository.findBySessionId(session.getSessionId())
                .map(order -> {
                    hydrateOrderLines(order);
                    if ("REFUNDED".equals(order.getStatus())) {
                        return 0;
                    }
                    int amount = order.getTotalAmountCents();
                    if (order.isInventoryDeducted()) {
                        List<VisionServiceClient.RecognizedItem> items = order.getLines().stream()
                                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(), 1f))
                                .toList();
                        var batchBySku = order.getLines().stream()
                                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                                .collect(java.util.stream.Collectors.toMap(
                                        com.aicabinet.trade.domain.CabinetOrderLine::getSkuId,
                                        com.aicabinet.trade.domain.CabinetOrderLine::getBatchNo,
                                        (a, b) -> a));
                        inventoryService.restoreForOrder(order.getDeviceId(), items, batchBySku);
                        order.setInventoryDeducted(false);
                    }
                    orderPaymentService.refundOrder(order, amount, "争议免单退款");
                    order.setStatus("REFUNDED");
                    orderRepository.save(order);
                    revenueSplitService.voidSplitOnFullRefund(order.getOrderId());
                    log.info("争议免单退款 session={} order={} refund={} channel={}",
                            session.getSessionId(), order.getOrderId(), amount, order.getPayChannel());
                    return amount;
                })
                .orElse(0);
    }

    private OrderDto finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());
        CabinetOrder order = buildOrder(session, items);
        CouponService.BestCoupon appliedCoupon = selectBestCouponForOrder(order);
        boolean unpaid = false;
        if (!userValidationService.canChargeViaPasswordFree(session.getUserId(), session.getEntryChannel())) {
            try {
                userValidationService.validateSufficientBalanceForCharge(
                        session.getUserId(), order.getTotalAmountCents());
            } catch (BalanceInsufficientException e) {
                unpaid = true;
                // 优惠券等到补扣成功再核销，避免关单占券
                appliedCoupon = null;
            }
        }
        var batchBySku = inventoryService.deductForOrder(
                session.getDeviceId(), items, session.getSessionId(), session.getGravityDeltas());
        applyBatchNos(order, batchBySku);
        order.setInventoryDeducted(true);
        order.setStatus(unpaid ? "PENDING" : "PAID");
        // Persist order before charge/coupon mark — payment_operation & user_coupon FK to cabinet_order
        orderRepository.save(order);
        persistOrderLines(order);

        if (unpaid) {
            session.setOrderId(order.getOrderId());
            sessionRepository.save(session);
            videoArchiveService.archiveAfterSettlement(session, order.getLines());
            log.info("unpaid order created session={} order={} amount={}",
                    session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
            return toDto(order);
        }

        try {
            orderPaymentService.chargeOrder(order);
        } catch (ResponseStatusException e) {
            if (isInsufficientBalance(e)) {
                order.setStatus("PENDING");
                orderRepository.save(order);
                session.setOrderId(order.getOrderId());
                sessionRepository.save(session);
                videoArchiveService.archiveAfterSettlement(session, order.getLines());
                log.info("unpaid order after charge fail session={} order={} amount={}",
                        session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
                return toDto(order);
            }
            throw e;
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
            int pts = memberService.onOrderPaid(session.getUserId(), order.getTotalAmountCents(), order.getOrderId());
            if (pts > 0) {
                log.info("member points earned userId={} order={} points={}",
                        session.getUserId(), order.getOrderId(), pts);
            }
        } catch (Exception e) {
            log.warn("member points reward failed order={}", order.getOrderId(), e);
        }
        videoArchiveService.archiveAfterSettlement(session, order.getLines());
        log.info("settled session={} order={} amount={} couponDiscount={} channel={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents(),
                order.getCouponDiscountCents(), order.getPayChannel());
        return toDto(order);
    }

    private static boolean isInsufficientBalance(ResponseStatusException e) {
        if (e.getStatusCode() != HttpStatus.PRECONDITION_FAILED) {
            return false;
        }
        String reason = e.getReason();
        return reason != null && (reason.contains("余额") || reason.toLowerCase().contains("insufficient"));
    }

    /** Pick best coupon and rewrite payable; caller must markUsed after order is persisted (FK). */
    private CouponService.BestCoupon selectBestCouponForOrder(CabinetOrder order) {
        int subtotal = order.getTotalAmountCents();
        order.setOriginalAmountCents(subtotal);
        if (subtotal <= 0 || order.getUserId() == null) {
            return null;
        }
        var best = couponService.selectBestCoupon(order.getUserId(), subtotal);
        if (best.isEmpty()) {
            return null;
        }
        var pick = best.get();
        int discount = Math.min(pick.discountCents(), subtotal);
        order.setCouponId(pick.couponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        log.info("auto applied coupon order={} couponId={} discount={} payable={}",
                order.getOrderId(), pick.couponId(), discount, order.getTotalAmountCents());
        return pick;
    }

    private void applyItemsToOrder(CabinetOrder order, List<VisionServiceClient.RecognizedItem> items) {
        order.getLines().clear();
        int total = 0;
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuCatalog sku = skuCatalogRepository.findById(item.skuId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            ApiMessages.SKU_NOT_FOUND + "：" + item.skuId()));
            int unitPrice = skuPricingService.resolveUnitPriceCents(order.getDeviceId(), sku);
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
            order.addLine(line);
        }
        order.setTotalAmountCents(total);
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
        order.setOrderId("O" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setSessionId(session.getSessionId());
        order.setUserId(session.getUserId());
        order.setDeviceId(session.getDeviceId());
        order.setStatus("PAID");
        applyItemsToOrder(order, items);
        return order;
    }

    @Transactional(readOnly = true)
    public OrderDto getOrderBySession(String sessionId) {
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

    private OrderDto toDto(CabinetOrder order) {
        hydrateOrderLines(order);
        int pointsEarned = 0;
        try {
            pointsEarned = memberService.findOrderEarnPoints(order.getUserId(), order.getOrderId());
        } catch (Exception ignored) {
            // points lookup must not break order detail
        }
        Integer couponDiscount = order.getCouponDiscountCents() > 0
                ? Integer.valueOf(order.getCouponDiscountCents())
                : null;
        // Avoid int/null nested ternary: javac unboxes the Integer branch and NPEs on null.
        Integer originalAmount = null;
        if (order.getOriginalAmountCents() > 0) {
            originalAmount = order.getOriginalAmountCents();
        } else if (order.getCouponDiscountCents() > 0) {
            originalAmount = order.getTotalAmountCents() + order.getCouponDiscountCents();
        }
        List<OrderLineDto> lines = order.getLines() == null
                ? List.of()
                : order.getLines().stream()
                        .map(l -> new OrderLineDto(
                                l.getSkuId(), l.getSkuName(), l.getQuantity(),
                                l.getUnitPriceCents(), l.getLineAmountCents(), l.getBatchNo()))
                        .toList();
        return new OrderDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getUserId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                lines,
                order.getStatus(),
                order.getPayChannel() != null ? order.getPayChannel() : "BALANCE",
                order.getPaymentOperationId(), order.getBalanceBeforeCents(), order.getBalanceAfterCents(),
                order.getCreatedAt(),
                couponDiscount,
                originalAmount,
                pointsEarned > 0 ? pointsEarned : null,
                refundPolicyService != null
                        ? refundPolicyService.resolveForDevice(order.getDeviceId()).name()
                        : null
        );
    }
}
