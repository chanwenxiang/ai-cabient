package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SettlementVisionAsyncService settlementVisionAsyncService;
    private final SettlementWaiveRefundService settlementWaiveRefundService;
    private final SettlementConfirmDisputeService settlementConfirmDisputeService;
    private final SettlementOrderFinalizeService settlementOrderFinalizeService;
    private final SettlementRecognitionService settlementRecognitionService;
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
                             SettlementVisionAsyncService settlementVisionAsyncService,
                             SettlementWaiveRefundService settlementWaiveRefundService,
                             SettlementConfirmDisputeService settlementConfirmDisputeService,
                             SettlementOrderFinalizeService settlementOrderFinalizeService,
                             SettlementRecognitionService settlementRecognitionService,
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
        this.settlementVisionAsyncService = settlementVisionAsyncService;
        this.settlementWaiveRefundService = settlementWaiveRefundService;
        this.settlementConfirmDisputeService = settlementConfirmDisputeService;
        this.settlementOrderFinalizeService = settlementOrderFinalizeService;
        this.settlementRecognitionService = settlementRecognitionService;
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
                recognition = settlementRecognitionService.withGravityFallback(session, recognition);
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
        return settlementRecognitionService.processRecognitionResultUnlocked(session, recognition);
    }

    /**
     * 识别不可用：短事务建争议单后抛 {@link DisputeRequiredException}（方法签名供调用方 return）。
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class})
    public OrderReadModel escalateVisionUnavailable(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult unavailable) {
        settlementRecognitionService.escalateToDispute(session, unavailable,
                "识别服务暂时不可用，已转人工审核，本次暂未扣款");
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
            return settlementRecognitionService.processRecognitionResultUnlocked(
                    session, recognition, allowDevFallback);
        });
    }

    String gravityDeltasForInventory(ShoppingSession session) {
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

    public void submitAsyncRecognition(ShoppingSession session) {
        settlementVisionAsyncService.submitAsyncRecognition(session);
    }

    @Transactional
    public void persistRecognitionTaskId(String sessionId, String taskId) {
        settlementVisionAsyncService.persistRecognitionTaskId(sessionId, taskId);
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
        return settlementConfirmDisputeService.confirmDisputedItems(session, items);
    }

    /** 免单：退还该会话已扣款项（原路退回）；默认回库（兼容历史免单=误识别）。 */
    public int waiveAndRefund(ShoppingSession session) {
        return settlementWaiveRefundService.waiveAndRefund(session);
    }

    /**
     * 免单/全额退款。无外层长事务：库存短事务 → 退款（可含渠道 HTTP）→ 状态短事务。
     *
     * @param restoreInventory true=退货退款回库；false=仅退款不回库（货已离柜）
     */
    public int waiveAndRefund(ShoppingSession session, boolean restoreInventory) {
        return settlementWaiveRefundService.waiveAndRefund(session, restoreInventory);
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

        List<CabinetOrderLine> remaining = SettlementPartialRefundMath.buildRemainingLines(
                SettlementPartialRefundMath.mergeLinesBySku(order.getLines()), partition.refundQtyBySku());
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
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(order.getLines());
        Map<String, Integer> refundQtyBySku =
                SettlementPartialRefundMath.validatePartialRefundQuantities(bySku, refundLines);
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
        CabinetOrder scratch = SettlementPartialRefundMath.copyOrderForPartialRefundEstimate(order);
        hydrateOrderLines(scratch);
        if (scratch.getLines() == null || scratch.getLines().isEmpty()) {
            return 0;
        }
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(scratch.getLines());
        Map<String, Integer> refundQtyBySku =
                SettlementPartialRefundMath.validatePartialRefundQuantities(bySku, refundLines);
        int priorPayable = Math.max(0, scratch.getTotalAmountCents());
        List<CabinetOrderLine> remaining =
                SettlementPartialRefundMath.buildRemainingLines(bySku, refundQtyBySku);
        scratch.setLines(remaining);
        int newSubtotal = remaining.stream().mapToInt(CabinetOrderLine::getLineAmountCents).sum();
        applyPartialRefundPayablePreview(scratch, newSubtotal);
        recalculatePayableAfterLineChange(scratch);
        return Math.max(0, priorPayable - scratch.getTotalAmountCents());
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

    OrderReadModel finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        return settlementOrderFinalizeService.finalizeOrder(session, items);
    }

    void applyItemsToOrder(CabinetOrder order, List<VisionServiceClient.RecognizedItem> items) {
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
    void recalculatePayableAfterLineChange(CabinetOrder order) {
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

    static void applyBatchNos(CabinetOrder order, java.util.Map<String, String> batchBySku) {
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

    /**
     * 仅同包服务在归属校验后调用；勿对 Controller / 跨模块公开，防 IDOR 绕过。
     */
    @Transactional(readOnly = true)
    OrderReadModel getOrderBySession(String sessionId) {
        return orderRepository.findBySessionId(sessionId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
    }

    void replaceOrderLines(CabinetOrder order) {
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

    void hydrateOrderLines(CabinetOrder order) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        if (order.getLines() != null && !order.getLines().isEmpty()) {
            return;
        }
        order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
    }

    OrderReadModel toDto(CabinetOrder order) {
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

    <T> T runWithSessionSettleLock(String sessionId, java.util.function.Supplier<T> action) {
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
