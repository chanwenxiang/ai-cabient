package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final SettlementService settlementService;
    private final DisputeService disputeService;
    private final OrderViewAssembler orderViewAssembler;

    public OrderService(CabinetOrderMapper orderRepository,
                        CabinetOrderLineMapper orderLineRepository,
                        SettlementService settlementService,
                        @Lazy DisputeService disputeService,
                        OrderViewAssembler orderViewAssembler) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.settlementService = settlementService;
        this.disputeService = disputeService;
        this.orderViewAssembler = orderViewAssembler;
    }

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listMyOrders(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<CabinetOrder> result = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSummary).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    /** 待支付/处理中订单数（消息中心角标）。 */
    @Transactional(readOnly = true)
    public long countMyPendingOrders(Long userId) {
        return orderRepository.countByUserIdAndStatusIn(
                userId, java.util.List.of("PENDING", "PROCESSING", "UNPAID"));
    }

    @Transactional(readOnly = true)
    public OrderReadModel getMyOrder(Long userId, String orderId) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional
    public OrderRefundResultDto refundMyOrder(Long userId, String orderId, OrderRefundRequest request) {
        return disputeService.refundByConsumer(userId, orderId, request);
    }

    private OrderReadModel toSummary(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
        return orderViewAssembler.assembleSummary(
                order, order.getLines(), null, null, null, null);
    }
}
