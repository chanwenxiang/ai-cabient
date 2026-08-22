package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.util.BizIds;
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
import java.util.Map;
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
                             SystemConfigService systemConfigService) {
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
            // 沙箱：gravity-fill（视觉空+重力有货）允许按重力结算；错配仍禁止静默扣款
            if (!blocksSilentSettle(recognition) || allowsSandboxGravityFillSettle(recognition)) {
                OrderDto stagingOrder = tryStagingGravitySettle(session);
                if (stagingOrder != null) {
                    return stagingOrder;
                }
            }
            // 本地 mock：有重力扣减证据时按购物车结算（OBS-012）；纯 mock 无证据仍进审单
            if (allowDevFallback) {
                OrderDto cartOrder = tryDevMockEvidenceSettle(session, recognition);
                if (cartOrder != null) {
                    return cartOrder;
                }
            }
            escalateToDispute(session, recognition, reviewReasonFor(recognition));
        }

        if (allowDevFallback && securityProperties.mockEnabled()) {
            // mock 标称结果不可当作生产精度自动扣款；有重力证据或沙箱 gravity-fill 除外
            if (blocksSilentSettle(recognition)) {
                if (allowsSandboxGravityFillSettle(recognition)) {
                    OrderDto stagingOrder = tryStagingGravitySettle(session);
                    if (stagingOrder != null) {
                        return stagingOrder;
                    }
                }
                OrderDto cartOrder = tryDevMockEvidenceSettle(session, recognition);
                if (cartOrder != null) {
                    return cartOrder;
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

    /**
     * 视觉无商品时是否自动零结。
     * <ul>
     *   <li>重力净扣减为空，且会话带重力字段（含 {@code []}）→ 传感器佐证未取货/已放回</li>
     *   <li>无重力字段 → 仅 mock/staging 自动零结，生产进审单</li>
     * </ul>
     */
    private boolean shouldAutoCompleteEmptyCart(ShoppingSession session) {
        List<VisionServiceClient.RecognizedItem> gravityItems =
                gravityHelper.toRecognizedItems(session.getGravityDeltas());
        if (gravityItems != null && !gravityItems.isEmpty()) {
            return false;
        }
        if (session.getGravityDeltas() != null) {
            return true;
        }
        if (systemConfigService.getBoolean(SystemConfigService.SETTLEMENT_EMPTY_AUTO_NO_GRAVITY, false)) {
            return true;
        }
        return securityProperties.mockEnabled()
                || stagingProperties.stagingMode()
                || stagingProperties.gravityFallbackSettle();
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

    /**
     * 本地演示：{@code AICABINET_MOCK_ENABLED} 下，只要会话有重力扣减证据，即按重力 finalize → PAID。
     * 覆盖 mock-v1 / gravity-fill / gravity-mismatch（演示柜模拟器常带重力，视觉 mock 易错配）。
     * 生产（mock 关闭）不走此分支；无重力证据返回 null，由调用方进审单。
     */
    private OrderDto tryDevMockEvidenceSettle(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
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

    @Transactional(noRollbackFor = {BalanceInsufficientException.class})
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

        // 先校验余额再动库存/账本，避免 412 触发 UnexpectedRollbackException（BUG-007）
        if (delta > 0 && !userValidationService.canChargeViaPasswordFree(
                session.getUserId(), session.getEntryChannel())) {
            userValidationService.validateSufficientBalanceForCharge(session.getUserId(), delta);
        }

        if (order.isInventoryDeducted()) {
            var adjustedBatches = inventoryService.adjustForOrder(
                    session.getDeviceId(), oldItems, items, batchBySku);
            applyBatchNos(order, adjustedBatches);
        } else {
            var deductedBatches = inventoryService.deductForOrder(
                    session.getDeviceId(), items, session.getSessionId(), session.getGravityDeltas());
            applyBatchNos(order, deductedBatches);
            order.setInventoryDeducted(true);
        }
        orderPaymentService.applyPaymentDelta(order, delta);
        // 三端一致：确认/改单后退出争议态（DisputeService 结案兜底也会对齐）
        if ("DISPUTED".equals(order.getStatus())) {
            order.setStatus("PAID");
        }
        if (delta != 0) {
            revenueSplitService.resyncSplitForOrder(order);
        }
        orderRepository.save(order);
        replaceOrderLines(order);
        log.info("dispute adjust session={} order={} original={} final={} delta={}",
                session.getSessionId(), order.getOrderId(), original, finalTotal, delta);
        return new ConfirmDisputeResult(toDto(order), original, finalTotal, delta);
    }

    /** 免单：退还该会话已扣款项（原路退回）；默认回库（兼容历史免单=误识别）。 */
    @Transactional
    public int waiveAndRefund(ShoppingSession session) {
        return waiveAndRefund(session, true);
    }

    /**
     * 免单/全额退款。
     *
     * @param restoreInventory true=退货退款回库；false=仅退款不回库（货已离柜）
     */
    @Transactional
    public int waiveAndRefund(ShoppingSession session, boolean restoreInventory) {
        return orderRepository.findBySessionId(session.getSessionId())
                .map(order -> {
                    hydrateOrderLines(order);
                    if ("REFUNDED".equals(order.getStatus())) {
                        return 0;
                    }
                    int amount = order.getTotalAmountCents();
                    boolean didRestore = false;
                    List<VisionServiceClient.RecognizedItem> items = order.getLines().stream()
                            .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(), 1f))
                            .toList();
                    var batchBySku = order.getLines().stream()
                            .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                            .collect(java.util.stream.Collectors.toMap(
                                    com.aicabinet.trade.domain.CabinetOrderLine::getSkuId,
                                    com.aicabinet.trade.domain.CabinetOrderLine::getBatchNo,
                                    (a, b) -> a));
                    if (restoreInventory && order.isInventoryDeducted()) {
                        inventoryService.restoreForOrder(order.getDeviceId(), items, batchBySku);
                        order.setInventoryDeducted(false);
                        didRestore = true;
                    } else if (!restoreInventory && order.isInventoryDeducted()) {
                        // 销售时已扣库；仅退款不回库 → 记 REFUND_KEPT 审计流水，禁止二次报损扣库
                        inventoryService.recordRefundKeptGoods(
                                order.getDeviceId(), items, batchBySku, order.getOrderId());
                    }
                    orderPaymentService.refundOrder(order, amount,
                            restoreInventory ? "争议免单退款(回库)" : "争议免单退款(不回库)");
                    order.setStatus("REFUNDED");
                    orderRepository.save(order);
                    revenueSplitService.voidSplitOnFullRefund(order.getOrderId());
                    log.info("争议免单退款 session={} order={} refund={} channel={} restoreInventory={} didRestore={}",
                            session.getSessionId(), order.getOrderId(), amount, order.getPayChannel(),
                            restoreInventory, didRestore);
                    return amount;
                })
                .orElse(0);
    }

    /**
     * 按行部分退款（竞品口径）：指定 SKU/数量退款；行级或默认决定是否回库。
     * 退完全部行 → {@code REFUNDED}；否则 → {@code PARTIAL_REFUNDED}。
     */
    @Transactional
    public PartialRefundResult partialRefund(CabinetOrder order,
                                             List<OrderRefundRequest.PartialRefundLine> refundLines,
                                             boolean defaultRestore,
                                             String reason) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定要退款的商品行");
        }
        hydrateOrderLines(order);
        if (order.getLines() == null || order.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单无商品行，无法按行退款");
        }
        Map<String, CabinetOrderLine> bySku = new java.util.LinkedHashMap<>();
        for (CabinetOrderLine line : order.getLines()) {
            if (line.getSkuId() == null) {
                continue;
            }
            bySku.merge(line.getSkuId(), line, (a, b) -> {
                a.setQuantity(a.getQuantity() + b.getQuantity());
                a.setLineAmountCents(a.getLineAmountCents() + b.getLineAmountCents());
                return a;
            });
        }
        List<VisionServiceClient.RecognizedItem> restoreItems = new java.util.ArrayList<>();
        List<VisionServiceClient.RecognizedItem> keptItems = new java.util.ArrayList<>();
        Map<String, String> batchBySku = new java.util.HashMap<>();
        int refundCents = 0;
        boolean anyRestored = false;
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
            boolean restore = req.restoreInventory() != null ? req.restoreInventory() : defaultRestore;
            int unit = line.getUnitPriceCents();
            refundCents += unit * req.quantity();
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
        if (refundCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额为 0");
        }
        if (refundCents > order.getTotalAmountCents()) {
            refundCents = order.getTotalAmountCents();
        }

        // 按 SKU 合并后缩减订单行（同 SKU 多行数量已合并）
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
        order.setLines(remaining);
        int newTotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        order.setTotalAmountCents(Math.max(0, newTotal));
        // 优惠券：部分退后重算门槛；不满足则退还券
        couponService.recalcOrRestoreAfterPartialRefund(order, newTotal);

        if (order.isInventoryDeducted()) {
            if (!restoreItems.isEmpty()) {
                inventoryService.restoreForOrder(order.getDeviceId(), restoreItems, batchBySku);
            }
            if (!keptItems.isEmpty()) {
                inventoryService.recordRefundKeptGoods(
                        order.getDeviceId(), keptItems, batchBySku, order.getOrderId());
            }
            if (remaining.isEmpty()) {
                order.setInventoryDeducted(false);
            }
        }

        int priorRefunded = Math.max(0, order.getRefundedCents());
        orderPaymentService.refundOrder(order, refundCents, reason == null ? "按行部分退款" : reason);
        // 支付层正常会累加 refundedCents；演示账号早退 / 历史路径漏写时在此兜底，避免 PARTIAL_REFUNDED 金额为 0
        if (order.getRefundedCents() < priorRefunded + refundCents) {
            order.setRefundedCents(priorRefunded + refundCents);
        }
        if (order.getRefundedAt() == null) {
            order.setRefundedAt(java.time.Instant.now());
        }
        boolean full = remaining.isEmpty() || order.getTotalAmountCents() <= 0;
        order.setStatus(full ? "REFUNDED" : "PARTIAL_REFUNDED");
        if (full) {
            order.setRefundedAt(java.time.Instant.now());
            revenueSplitService.voidSplitOnFullRefund(order.getOrderId());
        } else {
            revenueSplitService.resyncSplitForOrder(order);
        }
        orderRepository.save(order);
        replaceOrderLines(order);
        log.info("partial refund order={} refundCents={} status={} restoredLines={} keptLines={}",
                order.getOrderId(), refundCents, order.getStatus(), restoreItems.size(), keptItems.size());
        return new PartialRefundResult(refundCents, order.getStatus(), anyRestored);
    }

    public record PartialRefundResult(int refundedCents, String status, boolean anyInventoryRestored) {}

    @Transactional(readOnly = true)
    public int estimatePartialRefundCents(CabinetOrder order,
                                          List<OrderRefundRequest.PartialRefundLine> refundLines) {
        if (order == null || refundLines == null || refundLines.isEmpty()) {
            return 0;
        }
        hydrateOrderLines(order);
        Map<String, Integer> unitBySku = new java.util.HashMap<>();
        for (CabinetOrderLine line : order.getLines()) {
            if (line.getSkuId() != null) {
                unitBySku.putIfAbsent(line.getSkuId(), line.getUnitPriceCents());
            }
        }
        int sum = 0;
        for (OrderRefundRequest.PartialRefundLine req : refundLines) {
            if (req == null || req.skuId() == null || req.quantity() <= 0) {
                continue;
            }
            sum += unitBySku.getOrDefault(req.skuId().trim(), 0) * req.quantity();
        }
        return Math.min(sum, Math.max(0, order.getTotalAmountCents()));
    }

    private OrderDto finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());
        CabinetOrder order = buildOrder(session, items);
        CouponService.BestCoupon appliedCoupon = selectBestCouponForOrder(order);
        boolean unpaid = false;
        if (!userValidationService.canChargeViaPasswordFree(session.getUserId(), session.getEntryChannel())) {
            try {
                int hold = ConsumerPreauthService.STATUS_FROZEN.equalsIgnoreCase(
                        session.getPreauthStatus() == null ? "" : session.getPreauthStatus())
                        ? Math.max(0, session.getPreauthCents()) : 0;
                userValidationService.validateSufficientBalanceForCharge(
                        session.getUserId(), order.getTotalAmountCents(), hold);
            } catch (BalanceInsufficientException e) {
                unpaid = true;
                // 优惠券等到补扣成功再核销，避免关单占券；同时清掉订单上的券字段，防止未占券却按折后价落 PENDING
                appliedCoupon = null;
                clearCouponSelection(order);
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
            videoArchiveService.archiveAfterSettlement(session);
            log.info("unpaid order created session={} order={} amount={}",
                    session.getSessionId(), order.getOrderId(), order.getTotalAmountCents());
            return toDto(order);
        }

        try {
            orderPaymentService.chargeOrder(order);
        } catch (ResponseStatusException e) {
            if (isInsufficientBalance(e)) {
                // 扣款失败转待支付：同样不占券，补扣时再选
                clearCouponSelection(order);
                order.setStatus("PENDING");
                orderRepository.save(order);
                session.setOrderId(order.getOrderId());
                sessionRepository.save(session);
                videoArchiveService.archiveAfterSettlement(session);
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
        order.setTotalAmountCents(total);
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
                                l.getUnitPriceCents(), l.getLineAmountCents(), l.getBatchNo(), l.getSlotId()))
                        .toList();
        Integer memberDiscount = order.getMemberDiscountCents() > 0
                ? Integer.valueOf(order.getMemberDiscountCents())
                : null;
        if (originalAmount == null && order.getMemberDiscountCents() > 0) {
            originalAmount = order.getTotalAmountCents()
                    + order.getCouponDiscountCents()
                    + order.getMemberDiscountCents();
        }
        String merchantId = null;
        try {
            DeviceInfo device = deviceValidationService.requireDevice(order.getDeviceId());
            merchantId = device.getMerchantId();
        } catch (Exception ignored) {
            // 详情仍可返回，商户号留空
        }
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
                refundPolicyService != null
                        ? refundPolicyService.resolveForDevice(order.getDeviceId()).name()
                        : null,
                memberDiscount,
                order.getPayTradeNo(),
                order.getRefundedAt(),
                order.isInventoryDeducted(),
                merchantId,
                Math.max(0, order.getRefundedCents())
        );
    }
}
