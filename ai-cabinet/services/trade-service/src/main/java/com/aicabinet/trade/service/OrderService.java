package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderSummaryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private final CabinetOrderRepository orderRepository;
    private final SettlementService settlementService;

    public OrderService(CabinetOrderRepository orderRepository, SettlementService settlementService) {
        this.orderRepository = orderRepository;
        this.settlementService = settlementService;
    }

    @Transactional(readOnly = true)
    public PageResult<OrderSummaryDto> listMyOrders(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<CabinetOrder> result = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toSummary).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public OrderDto getMyOrder(Long userId, String orderId) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        return settlementService.getOrderBySession(order.getSessionId());
    }

    private OrderSummaryDto toSummary(CabinetOrder order) {
        return new OrderSummaryDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                order.getStatus(),
                order.getLines().size(),
                order.getCreatedAt()
        );
    }
}
