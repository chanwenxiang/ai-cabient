package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 结算入口编排（从 {@link SettlementService} 拆出）：视觉 HTTP 在事务外，
 * 分布式锁内再进入短事务识别落单。
 */
@Service
public class SettlementSettleOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SettlementSettleOrchestrator.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final VisionServiceClient visionClient;
    private final DeviceValidationService deviceValidationService;
    private final SettlementRecognitionService settlementRecognitionService;
    private final SettlementService settlement;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final SettlementSettleOrchestrator self;

    public SettlementSettleOrchestrator(ShoppingSessionMapper sessionRepository,
                                        CabinetOrderMapper orderRepository,
                                        VisionServiceClient visionClient,
                                        DeviceValidationService deviceValidationService,
                                        SettlementRecognitionService settlementRecognitionService,
                                        @Lazy SettlementService settlement,
                                        @Lazy SettlementSettleOrchestrator self) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.visionClient = visionClient;
        this.deviceValidationService = deviceValidationService;
        this.settlementRecognitionService = settlementRecognitionService;
        this.settlement = settlement;
        this.self = self;
    }

    /**
     * 视觉识别 HTTP 在事务外；分布式锁内先识别再短事务落库/扣款，避免占用 DB 连接等待 vision。
     */
    public OrderReadModel settle(ShoppingSession session) {
        return settlement.runWithSessionSettleLock(session.getSessionId(), () -> {
            if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
                return settlement.toDto(orderRepository.findBySessionId(session.getSessionId()).get());
            }
            deviceValidationService.ensureSettlementAllowed(session.getDeviceId());

            VisionServiceClient.RecognitionResult recognition;
            try {
                recognition = visionClient.recognize(session);
                recognition = settlementRecognitionService.withGravityFallback(session, recognition);
            } catch (RestClientException | IllegalStateException e) {
                VisionServiceClient.RecognitionResult unavailable = new VisionServiceClient.RecognitionResult(
                        "UNAVAILABLE-" + session.getSessionId(), List.of(), 0f, true,
                        "vision-unavailable", List.of());
                log.warn("vision unavailable session={}", session.getSessionId(), e);
                // 经代理进入短事务写争议单，并抛出 DisputeRequiredException
                return self.escalateVisionUnavailable(session, unavailable);
            }
            return self.processRecognitionAfterVision(session, recognition);
        });
    }

    /** 识别已完成：短事务内 forUpdate + 落单/扣款。 */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionAfterVision(ShoppingSession session,
                                                        VisionServiceClient.RecognitionResult recognition) {
        sessionRepository.findByIdForUpdate(session.getSessionId());
        return settlementRecognitionService.processRecognitionResultUnlocked(session, recognition);
    }

    /**
     * 识别不可用：短事务建争议单后抛 {@link DisputeRequiredException}（方法签名供调用方 return）。
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class})
    public OrderReadModel escalateVisionUnavailable(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult unavailable) {
        settlementRecognitionService.escalateToDispute(session, unavailable,
                "识别服务暂时不可用，已转人工审核，本次暂未扣款");
        throw new IllegalStateException("unreachable after dispute escalate");
    }

    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                                   VisionServiceClient.RecognitionResult recognition) {
        return self.processRecognitionResult(session, recognition, true);
    }

    /**
     * 主流开门柜：高置信度自动扣款；存疑或未识别到 SKU 一律进人工申诉，先不扣款。
     *
     * @param allowDevFallback 为 false 时不注入 mock SKU（运营识别测试）
     */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                                   VisionServiceClient.RecognitionResult recognition,
                                                   boolean allowDevFallback) {
        return settlement.runWithSessionSettleLock(session.getSessionId(), () -> {
            sessionRepository.findByIdForUpdate(session.getSessionId());
            return settlementRecognitionService.processRecognitionResultUnlocked(
                    session, recognition, allowDevFallback);
        });
    }
}
