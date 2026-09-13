package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
@Service
@SuppressWarnings("java:S6539")
public class SettlementService {
    private static final String GRAVITY_MISMATCH = "gravity-mismatch";
    private static final String GRAVITY_FILL = "gravity-fill";

    private final ShoppingSessionMapper sessionRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final SettlementVisionAsyncService settlementVisionAsyncService;
    private final SettlementWaiveRefundService settlementWaiveRefundService;
    private final SettlementConfirmDisputeService settlementConfirmDisputeService;
    private final SettlementOrderFinalizeService settlementOrderFinalizeService;
    private final SettlementPartialRefundService settlementPartialRefundService;
    private final SettlementSettleOrchestrator settleOrchestrator;
    private final RevenueSplitService revenueSplitService;
    private final OrderPaymentService orderPaymentService;
    private final MerchantSkuPricingService skuPricingService;
    private final CouponService couponService;
    private final MemberService memberService;
    private final RefundPolicyService refundPolicyService;
    private final DeviceSlotMapper slotRepository;
    private final SystemConfigService systemConfigService;
    private final DistributedLockService distributedLockService;
    private final OrderViewAssembler orderViewAssembler;

    public SettlementService(ShoppingSessionMapper sessionRepository,
                             SkuCatalogMapper skuCatalogRepository,
                             CabinetOrderMapper orderRepository,
                             CabinetOrderLineMapper orderLineRepository,
                             SettlementVisionAsyncService settlementVisionAsyncService,
                             SettlementWaiveRefundService settlementWaiveRefundService,
                             SettlementConfirmDisputeService settlementConfirmDisputeService,
                             SettlementOrderFinalizeService settlementOrderFinalizeService,
                             SettlementPartialRefundService settlementPartialRefundService,
                             @Lazy SettlementSettleOrchestrator settleOrchestrator,
                             RevenueSplitService revenueSplitService,
                             OrderPaymentService orderPaymentService,
                             MerchantSkuPricingService skuPricingService,
                             CouponService couponService,
                             MemberService memberService,
                             RefundPolicyService refundPolicyService,
                             DeviceSlotMapper slotRepository,
                             SystemConfigService systemConfigService,
                             DistributedLockService distributedLockService,
                             OrderViewAssembler orderViewAssembler) {
        this.sessionRepository = sessionRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.settlementVisionAsyncService = settlementVisionAsyncService;
        this.settlementWaiveRefundService = settlementWaiveRefundService;
        this.settlementConfirmDisputeService = settlementConfirmDisputeService;
        this.settlementOrderFinalizeService = settlementOrderFinalizeService;
        this.settlementPartialRefundService = settlementPartialRefundService;
        this.settleOrchestrator = settleOrchestrator;
        this.revenueSplitService = revenueSplitService;
        this.orderPaymentService = orderPaymentService;
        this.skuPricingService = skuPricingService;
        this.couponService = couponService;
        this.memberService = memberService;
        this.refundPolicyService = refundPolicyService;
        this.slotRepository = slotRepository;
        this.systemConfigService = systemConfigService;
        this.distributedLockService = distributedLockService;
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
        return settleOrchestrator.settle(session);
    }

    /** 识别已完成：短事务内 forUpdate + 落单/扣款。 */
    public OrderReadModel processRecognitionAfterVision(ShoppingSession session,
                                                        VisionServiceClient.RecognitionResult recognition) {
        return settleOrchestrator.processRecognitionAfterVision(session, recognition);
    }

    /**
     * 识别不可用：短事务建争议单后抛 {@link DisputeRequiredException}（方法签名供调用方 return）。
     */
    public OrderReadModel escalateVisionUnavailable(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult unavailable) {
        return settleOrchestrator.escalateVisionUnavailable(session, unavailable);
    }

    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        return settleOrchestrator.processRecognitionResult(session, recognition);
    }

    /**
     * 主流开门柜：高置信度自动扣款；存疑或未识别到 SKU 一律进人工申诉，先不扣款。
     *
     * @param allowDevFallback 为 false 时不注入 mock SKU（运营识别测试）
     */
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition,
                                             boolean allowDevFallback) {
        return settleOrchestrator.processRecognitionResult(session, recognition, allowDevFallback);
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
        return PartialRefundResult.from(
                settlementPartialRefundService.partialRefund(order, refundLines, defaultRestore, reason));
    }

    /** @see SettlementPartialRefundService.PartialRefundPrep */
    public SettlementPartialRefundService.PartialRefundPrep preparePartialRefund(
            String orderId,
            List<OrderRefundRequest.PartialRefundLine> refundLines,
            boolean defaultRestore) {
        return settlementPartialRefundService.preparePartialRefund(orderId, refundLines, defaultRestore);
    }

    /** @see SettlementPartialRefundService#finalizePartialRefund */
    public PartialRefundResult finalizePartialRefund(SettlementPartialRefundService.PartialRefundPrep prep) {
        return PartialRefundResult.from(settlementPartialRefundService.finalizePartialRefund(prep));
    }

    /** 兼容旧调用方：与 {@link SettlementPartialRefundService.PartialRefundResult} 同结构。 */
    public record PartialRefundResult(int refundedCents, String status, boolean anyInventoryRestored) {
        public static PartialRefundResult from(SettlementPartialRefundService.PartialRefundResult r) {
            return new PartialRefundResult(r.refundedCents(), r.status(), r.anyInventoryRestored());
        }
    }

    public int estimatePartialRefundCents(CabinetOrder order,
                                          List<OrderRefundRequest.PartialRefundLine> refundLines) {
        return settlementPartialRefundService.estimatePartialRefundCents(order, refundLines);
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
