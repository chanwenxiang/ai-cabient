package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.OrderSummaryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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

    public OrderService(CabinetOrderMapper orderRepository,
                        CabinetOrderLineMapper orderLineRepository,
                        ShoppingSessionMapper sessionRepository,
                        MinioVideoService minioVideoService,
                        SettlementService settlementService,
                        @Lazy DisputeService disputeService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.sessionRepository = sessionRepository;
        this.minioVideoService = minioVideoService;
        this.settlementService = settlementService;
        this.disputeService = disputeService;
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

    private OrderSummaryDto toSummary(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
        String payChannel = order.getPayChannel();
        // 与运营后台一致：余额账本扣款以 BL- 操作号为准
        if (order.getPaymentOperationId() != null && order.getPaymentOperationId().startsWith("BL-")) {
            payChannel = "BALANCE";
        }
        if (payChannel == null || payChannel.isBlank()) {
            payChannel = "UNKNOWN";
        }
        return new OrderSummaryDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                order.getStatus(),
                payChannel,
                itemQty(order),
                buildLineSummary(order),
                Math.max(0, order.getCouponDiscountCents()),
                order.getCreatedAt(),
                Math.max(0, order.getMemberDiscountCents()),
                originalAmount(order),
                order.getRefundedAt(),
                Math.max(0, order.getRefundedCents()),
                order.getPayTradeNo(),
                order.getPaymentOperationId(),
                order.getDeviceName(),
                order.getMerchantName()
        );
    }

    private static int itemQty(CabinetOrder order) {
        var lines = order.getLines();
        if (lines == null || lines.isEmpty()) return 0;
        return lines.stream().mapToInt(l -> Math.max(0, l.getQuantity())).sum();
    }

    private static int originalAmount(CabinetOrder order) {
        if (order.getOriginalAmountCents() > 0) {
            return order.getOriginalAmountCents();
        }
        return order.getTotalAmountCents()
                + Math.max(0, order.getCouponDiscountCents())
                + Math.max(0, order.getMemberDiscountCents());
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
                    if (l.getSlotId() != null && !l.getSlotId().isBlank()) {
                        name += " ·货道" + l.getSlotId().trim();
                    }
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo().trim();
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
