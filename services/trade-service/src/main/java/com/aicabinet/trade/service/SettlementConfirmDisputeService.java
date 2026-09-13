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
 * 落单/改行等仍委托 {@link SettlementService} 的包内协作方法。
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
    private final SettlementConfirmDisputeService self;

    public SettlementConfirmDisputeService(ShoppingSessionMapper sessionRepository,
                                           CabinetOrderMapper orderRepository,
                                           OrderPaymentService orderPaymentService,
                                           InventoryService inventoryService,
                                           UserValidationService userValidationService,
                                           RevenueSplitService revenueSplitService,
                                           @Lazy SettlementService settlement,
                                           @Lazy SettlementConfirmDisputeService self) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderPaymentService = orderPaymentService;
        this.inventoryService = inventoryService;
        this.userValidationService = userValidationService;
        this.revenueSplitService = revenueSplitService;
        this.settlement = settlement;
        this.self = self;
    }

    /**
     * 争议确认清单。无外层长事务包裹支付渠道：
     * 首次落单仍同事务 {@code chargeOrder}；改单为库存短事务 → 支付差额 → 状态短事务。
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
                orderPaymentService.applyPaymentDelta(prep.order(), prep.paymentDelta());
            }
            return self.finalizeConfirmDispute(prep);
        });
    }

    record ConfirmDisputePrep(
            boolean firstChargeDone,
            SettlementService.ConfirmDisputeResult result,
            CabinetOrder order,
            int originalPayable,
            int finalTotal,
            int paymentDelta) {
        static ConfirmDisputePrep firstCharge(SettlementService.ConfirmDisputeResult result) {
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
            OrderReadModel order = settlement.finalizeOrder(session, items);
            int amount = order.totalAmountCents();
            return ConfirmDisputePrep.firstCharge(
                    new SettlementService.ConfirmDisputeResult(order, 0, amount, amount));
        }

        CabinetOrder order = existing.get();
        settlement.hydrateOrderLines(order);
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
        settlement.applyItemsToOrder(order, items);
        settlement.recalculatePayableAfterLineChange(order);
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
            SettlementService.applyBatchNos(order, adjustedBatches);
        } else {
            var deductedBatches = inventoryService.deductForOrder(
                    session.getDeviceId(), items, order.getOrderId(),
                    settlement.gravityDeltasForInventory(session));
            SettlementService.applyBatchNos(order, deductedBatches);
            order.setInventoryDeducted(true);
        }
        orderRepository.save(order);
        settlement.replaceOrderLines(order);
        log.info("dispute confirm prepare session={} order={} original={} final={} delta={}",
                sessionId, order.getOrderId(), originalPayable, finalTotal, delta);
        return ConfirmDisputePrep.adjust(order, originalPayable, finalTotal, delta);
    }

    @Transactional
    public SettlementService.ConfirmDisputeResult finalizeConfirmDispute(ConfirmDisputePrep prep) {
        CabinetOrder order = orderRepository.findByIdForUpdate(prep.order().getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        settlement.hydrateOrderLines(order);
        if ("DISPUTED".equals(order.getStatus())) {
            order.setStatus("PAID");
        }
        if (prep.paymentDelta() != 0) {
            revenueSplitService.adjustSplitAfterOrderChange(order, prep.originalPayable());
        }
        orderRepository.save(order);
        log.info("dispute confirm finalize order={} original={} final={} delta={}",
                order.getOrderId(), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
        return new SettlementService.ConfirmDisputeResult(
                settlement.toDto(order), prep.originalPayable(), prep.finalTotal(), prep.paymentDelta());
    }
}
