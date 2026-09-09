package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final ShoppingSessionMapper sessionRepository;
    private final MinioVideoService minioVideoService;
    private final SettlementService settlementService;
    private final DisputeService disputeService;
    private final OrderViewAssembler orderViewAssembler;

    public OrderService(CabinetOrderMapper orderRepository,
                        CabinetOrderLineMapper orderLineRepository,
                        ShoppingSessionMapper sessionRepository,
                        MinioVideoService minioVideoService,
                        SettlementService settlementService,
                        @Lazy DisputeService disputeService,
                        OrderViewAssembler orderViewAssembler) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.sessionRepository = sessionRepository;
        this.minioVideoService = minioVideoService;
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

    @Transactional(readOnly = true)
    public OrderReadModel getMyOrder(Long userId, String orderId) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        return settlementService.getOrderBySession(order.getSessionId());
    }

    /** 消费者端播放本单购物录像（鉴权后流式输出，避免 minio:// 直连失败）。 */
    @Transactional(readOnly = true)
    public void streamMyOrderVideo(Long userId, String orderId,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        String sessionId = order.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该订单没有关联会话");
        }
        ShoppingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        String videoUri = session.getVideoUri();
        if (videoUri == null || videoUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该订单暂无购物视频");
        }
        minioVideoService.streamTo(videoUri, request, response);
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
