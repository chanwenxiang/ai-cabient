package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderSummaryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
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

    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final SettlementService settlementService;

    public OrderService(CabinetOrderMapper orderRepository,
                        CabinetOrderLineMapper orderLineRepository,
                        SettlementService settlementService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
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
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
        return new OrderSummaryDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                order.getStatus(),
                "BALANCE",
                order.getLines().size(),
                buildLineSummary(order),
                order.getCreatedAt()
        );
    }

    private static String buildLineSummary(CabinetOrder order) {
        var lines = order.getLines();
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String preview = lines.stream()
                .limit(2)
                .map(l -> {
                    String name = l.getSkuName() + " x" + l.getQuantity();
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo();
                    }
                    return name;
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (lines.size() > 2) {
            return preview + " 等" + lines.size() + "件";
        }
        return preview;
    }
}
