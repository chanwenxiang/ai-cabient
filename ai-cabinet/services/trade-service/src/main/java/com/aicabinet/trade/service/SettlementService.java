package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.messaging.VisionRecognitionProducer;
import com.aicabinet.trade.repository.*;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final ShoppingSessionRepository sessionRepository;
    private final UserAccountRepository userAccountRepository;
    private final SkuCatalogRepository skuCatalogRepository;
    private final CabinetOrderRepository orderRepository;
    private final VisionServiceClient visionClient;
    private final DisputeService disputeService;
    private final ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer;

    public SettlementService(ShoppingSessionRepository sessionRepository,
                             UserAccountRepository userAccountRepository,
                             SkuCatalogRepository skuCatalogRepository,
                             CabinetOrderRepository orderRepository,
                             VisionServiceClient visionClient,
                             @Lazy DisputeService disputeService,
                             ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer) {
        this.sessionRepository = sessionRepository;
        this.userAccountRepository = userAccountRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.orderRepository = orderRepository;
        this.visionClient = visionClient;
        this.disputeService = disputeService;
        this.visionRecognitionProducer = visionRecognitionProducer;
    }

    @Transactional(noRollbackFor = DisputeRequiredException.class)
    public OrderDto settle(ShoppingSession session) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }

        VisionServiceClient.RecognitionResult recognition = visionClient.recognize(session);
        return processRecognitionResult(session, recognition);
    }

    @Transactional(noRollbackFor = DisputeRequiredException.class)
    public OrderDto processRecognitionResult(ShoppingSession session,
                                             VisionServiceClient.RecognitionResult recognition) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        if (recognition.needReview()) {
            disputeService.createTicket(session, recognition, "recognition needs manual review");
            throw new DisputeRequiredException("recognition needs manual review");
        }

        return finalizeOrder(session, recognition.items());
    }

    public void submitAsyncRecognition(ShoppingSession session) {
        VisionRecognitionProducer producer = visionRecognitionProducer.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("vision async not enabled");
        }
        String taskId = "T-" + session.getSessionId();
        session.setRecognitionTaskId(taskId);
        sessionRepository.save(session);
        producer.publish(session.getSessionId(), session.getVideoUri(), taskId,
                session.getVideoClips(), session.getCameraFusionMode());
    }

    @Transactional
    public OrderDto settleManual(ShoppingSession session,
                                 List<VisionServiceClient.RecognizedItem> items) {
        if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(orderRepository.findBySessionId(session.getSessionId()).get());
        }
        return finalizeOrder(session, items);
    }

    private OrderDto finalizeOrder(ShoppingSession session,
                                   List<VisionServiceClient.RecognizedItem> items) {
        CabinetOrder order = buildOrder(session, items);
        deductBalance(session.getUserId(), order.getTotalAmountCents());
        orderRepository.save(order);
        session.setOrderId(order.getOrderId());
        sessionRepository.save(session);
        log.info("settled session={} order={} amount={}", session.getSessionId(),
                order.getOrderId(), order.getTotalAmountCents());
        return toDto(order);
    }

    private CabinetOrder buildOrder(ShoppingSession session,
                                    List<VisionServiceClient.RecognizedItem> items) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setSessionId(session.getSessionId());
        order.setUserId(session.getUserId());
        order.setDeviceId(session.getDeviceId());
        order.setStatus("PAID");

        int total = 0;
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuCatalog sku = skuCatalogRepository.findById(item.skuId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            ApiMessages.SKU_NOT_FOUND + "：" + item.skuId()));
            int lineAmount = sku.getPriceCents() * item.quantity();
            total += lineAmount;

            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId(sku.getSkuId());
            line.setSkuName(sku.getSkuName());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(sku.getPriceCents());
            line.setLineAmountCents(lineAmount);
            order.addLine(line);
        }
        order.setTotalAmountCents(total);
        return order;
    }

    private void deductBalance(Long userId, int amountCents) {
        if (userId >= com.aicabinet.common.constants.CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        account.setBalanceCents(account.getBalanceCents() - amountCents);
        userAccountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrderBySession(String sessionId) {
        return orderRepository.findBySessionId(sessionId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
    }

    private OrderDto toDto(CabinetOrder order) {
        return new OrderDto(
                order.getOrderId(),
                order.getSessionId(),
                order.getUserId(),
                order.getDeviceId(),
                order.getTotalAmountCents(),
                order.getLines().stream()
                        .map(l -> new OrderLineDto(
                                l.getSkuId(), l.getSkuName(), l.getQuantity(),
                                l.getUnitPriceCents(), l.getLineAmountCents()))
                        .toList(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
