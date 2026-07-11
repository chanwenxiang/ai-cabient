package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.messaging.VisionRecognitionProducer;
import com.aicabinet.trade.repository.*;
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

    private final ShoppingSessionRepository sessionRepository;
    private final SkuCatalogRepository skuCatalogRepository;
    private final CabinetOrderRepository orderRepository;
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

    public SettlementService(ShoppingSessionRepository sessionRepository,
                             SkuCatalogRepository skuCatalogRepository,
                             CabinetOrderRepository orderRepository,
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
                             MerchantSkuPricingService skuPricingService) {
        this.sessionRepository = sessionRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.orderRepository = orderRepository;
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
    }

    /** 人工审核后确认清单：无订单则首次扣款；有订单则按差额退/补。 */
    public record ConfirmDisputeResult(
            OrderDto order,
            int originalAmountCents,
            int finalAmountCents,
            int adjustmentCents
    ) {}

    @Transactional(noRollbackFor = DisputeRequiredException.class)
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

    @Transactional(noRollbackFor = DisputeRequiredException.class)
    public OrderDto processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        return processRecognitionResult(session, recognition, true);
    }

    /**
     * 主流开门柜：高置信度自动扣款；存疑或未识别到 SKU 一律进人工申诉，先不扣款。
     *
     * @param allowDevFallback 为 false 时不注入 mock SKU（运营识别测试）
     */
    @Transactional(noRollbackFor = DisputeRequiredException.class)
    public OrderDto processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition,
                                             boolean allowDevFallback) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        recognition = withGravityFallback(session, recognition);

        if (allowDevFallback && securityProperties.mockEnabled()) {
            List<VisionServiceClient.RecognizedItem> cartItems =
                    gravityHelper.toRecognizedItems(session.getGravityDeltas());
            log.info("dev mock settle session={} cartItems={}", session.getSessionId(), cartItems.size());
            return finalizeOrder(session, cartItems);
        }

        if (recognition.needReview()) {
            if (allowDevFallback && securityProperties.mockEnabled() && !recognition.items().isEmpty()) {
                log.warn("dev mock: skip manual review session={}", session.getSessionId());
                return finalizeOrder(session, recognition.items());
            }
            OrderDto stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
            escalateToDispute(session, recognition, "识别结果需人工审核");
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

        return finalizeOrder(session, recognition.items());
    }

    /** 预发 E2E：有重力扣减信号时优先按重力结算，避免无真实购物视频时误入争议。 */
    private OrderDto tryStagingGravitySettle(ShoppingSession session) {
        if (!stagingProperties.stagingMode()) {
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

    private VisionServiceClient.RecognitionResult withGravityFallback(ShoppingSession session,
                                                                      VisionServiceClient.RecognitionResult recognition) {
        List<VisionServiceClient.RecognizedItem> merged = gravityHelper.mergeWithVision(
                session.getGravityDeltas(), recognition.items());
        if (merged == recognition.items()) {
            return recognition;
        }
        return new VisionServiceClient.RecognitionResult(
                recognition.taskId(),
                merged,
                recognition.overallConfidence(),
                recognition.needReview(),
                recognition.modelVersion() + "+gravity",
                recognition.detectedClasses()
        );
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
        if ("REFUNDED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_REFUNDED);
        }

        List<VisionServiceClient.RecognizedItem> oldItems = order.getLines().stream()
                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(),
                        l.getConfidence() != null ? l.getConfidence() : 1f))
                .toList();

        int original = order.getTotalAmountCents();
        applyItemsToOrder(order, items);
        int finalTotal = order.getTotalAmountCents();
        int delta = finalTotal - original;

        if (order.isInventoryDeducted()) {
            inventoryService.adjustForOrder(session.getDeviceId(), oldItems, items);
        } else {
            var batchBySku = inventoryService.deductForOrder(
                    session.getDeviceId(), items, session.getSessionId(), session.getGravityDeltas());
            applyBatchNos(order, batchBySku);
            order.setInventoryDeducted(true);
        }
        orderPaymentService.applyPaymentDelta(order, delta);
        orderRepository.save(order);
        log.info("dispute adjust session={} order={} original={} final={} delta={}",
                session.getSessionId(), order.getOrderId(), original, finalTotal, delta);
        return new ConfirmDisputeResult(toDto(order), original, finalTotal, delta);
    }

    /** 免单：退还该会话已扣款项（原路退回）。 */
    @Transactional
    public int waiveAndRefund(ShoppingSession session) {
        return orderRepository.findBySessionId(session.getSessionId())
                .map(order -> {
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
                    log.info("dispute waive session={} order={} refund={} channel={}",
                            session.getSessionId(), order.getOrderId(), amount, order.getPayChannel());
                    return amount;
                })
                .orElse(0);
    }

    private OrderDto finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        deviceValidationService.ensureSettlementAllowed(session.getDeviceId());
        CabinetOrder order = buildOrder(session, items);
        var batchBySku = inventoryService.deductForOrder(
                session.getDeviceId(), items, session.getSessionId(), session.getGravityDeltas());
        applyBatchNos(order, batchBySku);
        order.setInventoryDeducted(true);
        orderPaymentService.chargeOrder(order);
        orderRepository.save(order);
        revenueSplitService.recordSplit(order);
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        log.info("settled session={} order={} amount={} channel={}",
                session.getSessionId(), order.getOrderId(), order.getTotalAmountCents(), order.getPayChannel());
        return toDto(order);
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

    private OrderDto toDto(CabinetOrder order) {
        return new OrderDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getUserId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                order.getLines().stream()
                        .map(l -> new OrderLineDto(
                                l.getSkuId(), l.getSkuName(), l.getQuantity(),
                                l.getUnitPriceCents(), l.getLineAmountCents(), l.getBatchNo()))
                        .toList(),
                order.getStatus(),
                order.getPayChannel() != null ? order.getPayChannel() : "BALANCE",
                order.getPaymentOperationId(), order.getBalanceBeforeCents(), order.getBalanceAfterCents(),
                order.getCreatedAt()
        );
    }
}
