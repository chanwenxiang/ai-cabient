package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 争议免单 / 全额退款（从 {@link SettlementService} 拆出，降低上帝类体积）。
 */
@Service
public class SettlementWaiveRefundService {

    private static final Logger log = LoggerFactory.getLogger(SettlementWaiveRefundService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final OrderPaymentService orderPaymentService;
    private final InventoryService inventoryService;
    private final RevenueSplitService revenueSplitService;
    private final DistributedLockService distributedLockService;
    private final SettlementWaiveRefundService self;

    public SettlementWaiveRefundService(ShoppingSessionMapper sessionRepository,
                                        CabinetOrderMapper orderRepository,
                                        CabinetOrderLineMapper orderLineRepository,
                                        OrderPaymentService orderPaymentService,
                                        InventoryService inventoryService,
                                        RevenueSplitService revenueSplitService,
                                        DistributedLockService distributedLockService,
                                        @Lazy SettlementWaiveRefundService self) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.orderPaymentService = orderPaymentService;
        this.inventoryService = inventoryService;
        this.revenueSplitService = revenueSplitService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    public int waiveAndRefund(ShoppingSession session) {
        return waiveAndRefund(session, true);
    }

    /**
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
                .collect(Collectors.toMap(
                        CabinetOrderLine::getSkuId,
                        CabinetOrderLine::getBatchNo,
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

    private void hydrateOrderLines(CabinetOrder order) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        if (order.getLines() != null && !order.getLines().isEmpty()) {
            return;
        }
        order.setLines(new ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
    }

    private <T> T runWithSessionSettleLock(String sessionId, java.util.function.Supplier<T> action) {
        String lockKey = SettlementService.sessionSettleLockKey(sessionId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话结算处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException | DisputeRequiredException | BalanceInsufficientException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }
}
