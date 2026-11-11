package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class OrderService {

    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final SettlementService settlementService;
    private final DisputeService disputeService;
    private final OrderViewAssembler orderViewAssembler;
    private final SkuCatalogMapper skuCatalogMapper;

    public OrderService(CabinetOrderMapper orderRepository,
                        CabinetOrderLineMapper orderLineRepository,
                        SettlementService settlementService,
                        @Lazy DisputeService disputeService,
                        OrderViewAssembler orderViewAssembler,
                        SkuCatalogMapper skuCatalogMapper) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.settlementService = settlementService;
        this.disputeService = disputeService;
        this.orderViewAssembler = orderViewAssembler;
        this.skuCatalogMapper = skuCatalogMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<OrderReadModel> listMyOrders(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<CabinetOrder> result = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        // R14-F2：跨单批量取图——旧写法逐单 toSummary → loadSkuImages 每单一次 sku_catalog
        // 点查（每页 +N 次）。改为整页收集行 → 一次 findByOrderIds + 一次 findBySkuIds。
        List<String> orderIds = result.getContent().stream()
                .map(o -> String.valueOf(o.getOrderId()))
                .toList();
        Map<String, String> pageImages = loadPageSkuImages(orderIds);
        return new PageResult<>(
                result.getContent().stream()
                        .map(o -> toSummary(o, pageImages))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    /** 整页所有订单行的 skuId → imageUrl 映射（两次批查，替代逐单 N 次 selectBatchIds）。 */
    private Map<String, String> loadPageSkuImages(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<CabinetOrderLine> allLines = orderLineRepository.findByOrderIds(orderIds);
            if (allLines == null || allLines.isEmpty()) {
                return Map.of();
            }
            List<String> skuIds = allLines.stream()
                    .map(CabinetOrderLine::getSkuId)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            if (skuIds.isEmpty()) {
                return Map.of();
            }
            Map<String, String> images = new java.util.HashMap<>();
            for (SkuCatalog sku : skuCatalogMapper.findBySkuIds(skuIds)) {
                if (sku != null && sku.getSkuId() != null && sku.getImageUrl() != null
                        && !sku.getImageUrl().isBlank()) {
                    images.put(sku.getSkuId().trim(), sku.getImageUrl().trim());
                }
            }
            return images;
        } catch (RuntimeException e) {
            return Map.of(); // 与 loadSkuImages 同口径：订单读面不因配图缺失而失败
        }
    }

    private OrderReadModel toSummary(CabinetOrder order, Map<String, String> pageImages) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new java.util.ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
        return orderViewAssembler.assemble(
                order, order.getLines(), null, null, null, null, false, pageImages);
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
