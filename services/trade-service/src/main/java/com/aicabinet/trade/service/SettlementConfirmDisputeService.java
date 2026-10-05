package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 争议确认清单（从 {@link SettlementService} 拆出，降低上帝类体积）。
 * 锁/落单委托 {@link SettlementService}；行改/DTO 委托 {@link SettlementOrderSupport}。
 */
@Service
public class SettlementConfirmDisputeService {

    private static final Logger log = LoggerFactory.getLogger(SettlementConfirmDisputeService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final OrderPaymentService orderPaymentService;
    private final InventoryService inventoryService;
    private final UserValidationService userValidationService;
    private final RevenueSplitService revenueSplitService;
    private final SettlementService settlement;
    private final SettlementOrderSupport orderSupport;
    private final OpsAlertDispatcher alertDispatcher;
    private final OpsExceptionService opsExceptionService;
    private final SettlementConfirmDisputeService self;

    public SettlementConfirmDisputeService(ShoppingSessionMapper sessionRepository,
                                           CabinetOrderMapper orderRepository,
                                           OrderPaymentService orderPaymentService,
                                           InventoryService inventoryService,
                                           UserValidationService userValidationService,
                                           RevenueSplitService revenueSplitService,
                                           OpsAlertDispatcher alertDispatcher,
                                           OpsExceptionService opsExceptionService,
                                           @Lazy SettlementService settlement,
                                           SettlementOrderSupport orderSupport,
                                           @Lazy SettlementConfirmDisputeService self) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderPaymentService = orderPaymentService;
        this.inventoryService = inventoryService;
        this.userValidationService = userValidationService;
        this.revenueSplitService = revenueSplitService;
        this.settlement = settlement;
        this.orderSupport = orderSupport;
        this.alertDispatcher = alertDispatcher;
        this.opsExceptionService = opsExceptionService;
        this.self = self;
    }

    /**
     * 争议确认清单。无外层长事务包裹支付渠道：
     * 首次落单仍同事务 {@code chargeOrder}；改单为库存短事务 → 支付差额 → 状态短事务。
     *
     * <p>审计 P2-3：{@code applyPaymentDelta} 内含独立短事务（渠道差额已扣/退即落账），若随后
     * {@code finalizeConfirmDispute} 失败，出现「钱已动、订单行/状态未对齐」——此时必须发 HIGH
     * 告警转人工，不能只把异常往上抛（原实现无任何告警，账实漂移隐形）。告警失败不影响异常传播。</p>
     */
    public SettlementService.ConfirmDisputeResult confirmDisputedItems(
            ShoppingSession session,
            List<VisionServiceClient.RecognizedItem> items) {
        return settlement.runWithSessionSettleLock(session.getSessionId(), () -> {
            ConfirmDisputePrep prep = self.prepareConfirmDispute(session.getSessionId(), items);
            if (prep.firstChargeDone()) {
                return prep.result();
            }
            if (prep.paymentDelta() != 0) {
                try {
                    orderPaymentService.applyPaymentDelta(prep.order(), prep.paymentDelta());
                    return self.finalizeConfirmDispute(prep);
                } catch (RuntimeException e) {
                    alertOnAdjustMismatch(prep, e);
                    throw e;
                }
            }
            return self.finalizeConfirmDispute(prep);
        });
    }

    /** 渠道差额已落账后的收尾失败：检账并告警（ADJUST_CHARGE:FINALIZED 存在即「钱已动」）。 */
    private void alertOnAdjustMismatch(ConfirmDisputePrep prep, RuntimeException cause) {
        try {
            orderSupport.hydrateOrderLines(prep.order());
            String summary = "争议确认补差后收尾失败，需人工对账：orderId=" + prep.order().getOrderId()
                    + " sessionId=" + prep.session().getSessionId()
                    + " delta=" + prep.paymentDelta() + "分"
                    + " original=" + prep.originalPayable() + "分"
                    + " final=" + prep.finalTotal() + "分"
                    + " cause=" + cause.getMessage();
            log.error("dispute confirm finalize failed after payment delta applied {}", summary, cause);
            // 审计 P2-3 复核修正：除渠道广播外必须落 ops 异常留痕（HIGH），保证运营台可见可认领
            opsExceptionService.report("DISPUTE_ADJUST_MISMATCH", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            null, prep.session().getSessionId(), prep.order().getOrderId(), null),
                    "争议补差账实漂移待人工", summary);
            alertDispatcher.send("DISPUTE", "争议补差账实漂移待人工", summary);
        } catch (RuntimeException alertEx) {
            log.warn("dispute adjust mismatch alert dispatch failed orderId={}: {}",
                    prep.order().getOrderId(), alertEx.toString());
        }
    }

    record ConfirmDisputePrep(
            boolean firstChargeDone,
            SettlementService.ConfirmDisputeResult result,
            CabinetOrder order,
            int originalPayable,
            int finalTotal,
            int paymentDelta,
            ShoppingSession session,
            List<VisionServiceClient.RecognizedItem> oldItems,
            List<VisionServiceClient.RecognizedItem> newItems,
            java.util.Map<String, String> batchBySku) {
        static ConfirmDisputePrep firstCharge(SettlementService.ConfirmDisputeResult result) {
            return new ConfirmDisputePrep(true, result, null, 0, 0, 0,
                    null, null, null, null);
        }

        static ConfirmDisputePrep adjust(CabinetOrder order, int originalPayable, int finalTotal, int paymentDelta,
                                         ShoppingSession session,
                                         List<VisionServiceClient.RecognizedItem> oldItems,
                                         List<VisionServiceClient.RecognizedItem> newItems,
                                         java.util.Map<String, String> batchBySku) {
            return new ConfirmDisputePrep(false, null, order, originalPayable, finalTotal, paymentDelta,
                    session, oldItems, newItems, batchBySku);
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
            OrderReadModel order = settlement.finalizeOrder(session, items);
            int amount = order.totalAmountCents();
            return ConfirmDisputePrep.firstCharge(
                    new SettlementService.ConfirmDisputeResult(order, 0, amount, amount));
        }

        CabinetOrder order = existing.get();
        orderSupport.hydrateOrderLines(order);
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_REFUNDED);
        }

        List<VisionServiceClient.RecognizedItem> oldItems = order.getLines().stream()
                .map(l -> new VisionServiceClient.RecognizedItem(l.getSkuId(), l.getQuantity(),
                        l.getConfidence() != null ? l.getConfidence() : 1f))
                .toList();
        var batchBySku = order.getLines().stream()
                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                .collect(Collectors.toMap(
                        com.aicabinet.trade.domain.CabinetOrderLine::getSkuId,
                        com.aicabinet.trade.domain.CabinetOrderLine::getBatchNo,
                        (a, b) -> a));

        int originalPayable = order.getTotalAmountCents();
        orderSupport.applyItemsToOrder(order, items);
        orderSupport.recalculatePayableAfterLineChange(order);
        int finalTotal = order.getTotalAmountCents();
        int delta = finalTotal - originalPayable;

        // 先校验余额再动库存/支付，避免 412 触发 UnexpectedRollbackException（BUG-007）
        if (delta > 0 && !userValidationService.canChargeViaPasswordFree(
                session.getUserId(), session.getEntryChannel())) {
            userValidationService.validateSufficientBalanceForCharge(session.getUserId(), delta);
        }

        // C06：prepare 只做纯读计算与预校验，不写库存/订单；
        // 库存调整挪到 finalize（支付差额成功之后），支付失败时不做任何库存变更。
        log.info("dispute confirm prepare session={} order={} original={} final={} delta={}",
                sessionId, order.getOrderId(), originalPayable, finalTotal, delta);
        return ConfirmDisputePrep.adjust(order, originalPayable, finalTotal, delta,
                session, oldItems, items, batchBySku);
    }

    @Transactional
    public SettlementService.ConfirmDisputeResult finalizeConfirmDispute(ConfirmDisputePrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        orderSupport.hydrateOrderLines(order);
        // 重放行改（与 prepare 同口径；结算分布式锁期间数据不变），再执行库存调整（C06）
        orderSupport.applyItemsToOrder(order, prep.newItems());
        orderSupport.recalculatePayableAfterLineChange(order);
        if ("DISPUTED".equals(order.getStatus())) {
            order.setStatus("PAID");
        }
        if (order.isInventoryDeducted()) {
            var adjustedBatches = inventoryService.adjustForOrder(
                    prep.session().getDeviceId(), prep.oldItems(), prep.newItems(), prep.batchBySku(),
                    order.getOrderId());
            SettlementOrderSupport.applyBatchNos(order, adjustedBatches);
        } else {
            var deductedBatches = inventoryService.deductForOrder(
                    prep.session().getDeviceId(), prep.newItems(), order.getOrderId(),
                    orderSupport.gravityDeltasForInventory(prep.session()));
            SettlementOrderSupport.applyBatchNos(order, deductedBatches);
            order.setInventoryDeducted(true);
        }
        if (prep.paymentDelta() != 0) {
            revenueSplitService.adjustSplitAfterOrderChange(order, prep.originalPayable());
        }
        orderRepository.save(order);
        orderSupport.replaceOrderLines(order);
        log.info("dispute confirm finalize order={} original={} final={} delta={}",
                order.getOrderId(), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
        return new SettlementService.ConfirmDisputeResult(
                orderSupport.toDto(order), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
    }
}
