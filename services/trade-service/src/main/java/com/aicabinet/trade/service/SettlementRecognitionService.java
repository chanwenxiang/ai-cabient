package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.recognition.RecognitionOutcome;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 识别结果决策管线（从 {@link SettlementService} 拆出）：审单旁路、空购零结、争议升级。
 * 落单委托 {@link SettlementService}；DTO 委托 {@link SettlementOrderSupport}。
 *
 * <p>重力兜底已随 DC-27 批2 删除：识别只认端侧上报（edge-results / Kafka 回调同一份 DTO 管道），
 * 没有「拿重力信号凑出结算成功」的通路。
 */
@Service
public class SettlementRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementRecognitionService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final SettlementConfidenceService confidenceService;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;
    private final SystemConfigService systemConfigService;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;
    private final SettlementService settlement;
    private final SettlementOrderSupport orderSupport;
    private final RecognitionResultWriter recognitionResultWriter;

    public SettlementRecognitionService(ShoppingSessionMapper sessionRepository,
                                        CabinetOrderMapper orderRepository,
                                        SettlementConfidenceService confidenceService,
                                        SecurityProperties securityProperties,
                                        StagingProperties stagingProperties,
                                        SystemConfigService systemConfigService,
                                        @Lazy DisputeService disputeService,
                                        ConsumerPreauthService consumerPreauthService,
                                        @Lazy SettlementService settlement,
                                        SettlementOrderSupport orderSupport,
                                        RecognitionResultWriter recognitionResultWriter) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.confidenceService = confidenceService;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
        this.systemConfigService = systemConfigService;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
        this.settlement = settlement;
        this.orderSupport = orderSupport;
        this.recognitionResultWriter = recognitionResultWriter;
    }

    /**
     * 识别结果落库（会话维度一份）——<b>唯一的运行期写入点</b>。
     *
     * <p>位置选在这里的理由：{@code processRecognitionResultUnlocked} 是三条路径的公共下游——
     * 同步关门（{@code SettlementSettleOrchestrator#processRecognitionAfterVision}）、
     * 端侧识别结果回调（{@code SettlementService#processRecognitionResult}）、开发上传识别；
     * 一处即可全覆盖。若挂在上游各个入口，三份代码会各自漂移，
     * 且历史上默认配置下异步投递通道（vision-async，2026-10-17 已整体退役）根本不启用，
     * 只挂那条入口等于没落库。
     *
     * <p>记录的是<b>归一之后</b>的识别结果（已含强制复核标记），即平台据此决策的那一份；
     * 端侧原始报文的逐字对账不在本表职责内。
     *
     * <p><b>失败语义</b>：与结算<b>共用同一个事务</b>（理由见 {@link RecognitionResultWriter} 类注释——
     * 外键会让独立事务与外层 {@code FOR UPDATE} 互锁）。因此落库失败会让结算一起回滚，这是刻意的
     * fail-hard：宁可让本次结算失败并可重试，也不接受「已扣款却没有识别记录」的对账黑洞。
     * 异常不在此处吞掉，交由上层按结算失败统一处理（会话置 FAILED + 运维事件）。
     */
    private void recordRecognitionResult(ShoppingSession session,
                                         RecognitionOutcome recognition) {
        RecognitionResultWriter.Outcome outcome =
                recognitionResultWriter.persist(session.getSessionId(), recognition);
        if (outcome != RecognitionResultWriter.Outcome.WRITTEN) {
            log.info("识别结果未落库 session={} outcome={}", session.getSessionId(), outcome);
        }
    }

    OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                    RecognitionOutcome recognition) {
        return processRecognitionResultUnlocked(session, recognition, true);
    }

    OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                    RecognitionOutcome recognition,
                                                    boolean allowDevFallback) {
        var existingOrder = orderRepository.findBySessionId(session.getSessionId());
        if (existingOrder.isPresent()) {
            return orderSupport.toDto(existingOrder.get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        recognition = forceReviewIfMockOrMismatch(recognition);
        if (recognition == null) {
            throw new IllegalStateException("recognition result is null after review normalize");
        }
        recordRecognitionResult(session, recognition);

        escalateIfHumanReviewRequired(session, recognition);
        OrderReadModel early = trySettleEmptyRecognition(session, recognition);
        if (early != null) {
            return early;
        }

        if (skipConfidenceGate(allowDevFallback)) {
            log.info("dev mock settle session={} cartItems={}",
                    session.getSessionId(), recognition.items().size());
            return settlement.finalizeOrder(session, recognition.items());
        }

        String confidenceReason = confidenceService.reviewReasonIfNeeded(recognition);
        if (confidenceReason != null
                && systemConfigService.getBoolean(SystemConfigService.DISPUTE_AUTO_OPEN, true)) {
            escalateToDispute(session, recognition, confidenceReason);
        } else if (confidenceReason != null) {
            log.info("dispute.auto_open=false, settle despite low confidence session={} reason={}",
                    session.getSessionId(), confidenceReason);
        }

        // 旧「识别入驻白名单」闸已随旧链退役（2026-10-11）：将邑链的准入=classId 映射激活
        // （未命中在 gateway 即 fail-closed 转 DISPUTED），置信度闸在上方 confidenceService。
        return settlement.finalizeOrder(session, recognition.items());
    }

    /**
     * 端侧显式 need_review（含 {@link #forceReviewIfMockOrMismatch} 强制标记）：一律进人工审核，
     * 不静默扣款——没有任何「拿重力/购物车证据凑成结算成功」的旁路。
     */
    private void escalateIfHumanReviewRequired(ShoppingSession session,
                                               RecognitionOutcome recognition) {
        if (recognition.needReview()) {
            escalateToDispute(session, recognition, humanReviewReason(recognition));
        }
    }

    /**
     * 本地 mock 环境不卡置信度闸（标称精度在 {@code forceReviewIfMockOrMismatch} 已被拦在静默结算之前，
     * 能走到这里的都是端侧/开发上报的明细）；生产的置信度闸始终生效。
     */
    private boolean skipConfidenceGate(boolean allowDevFallback) {
        return allowDevFallback && securityProperties.mockEnabled();
    }

    /** 识别无商品：自动零结 / 进审单。 */
    private OrderReadModel trySettleEmptyRecognition(ShoppingSession session,
                                                     RecognitionOutcome recognition) {
        if (!recognition.items().isEmpty()) {
            return null;
        }
        // 竞品口径：识别为空且政策允许自动零结 → 未取货/拿了又放回，自动零结；
        // 政策关闭时生产仍进争议（防摄像头静默失败）。
        if (shouldAutoCompleteEmptyCart()) {
            log.info("empty cart auto-complete session={} device={}",
                    session.getSessionId(), session.getDeviceId());
            return settlement.finalizeOrder(session, List.of());
        }
        escalateToDispute(session, recognition, "未识别到商品，需人工审核");
        return null;
    }

    /**
     * 识别无商品时是否自动零结（{@code settlement.empty_auto_complete}，默认 false 进争议）。
     * 沙箱/staging 与 mock 环境豁免：演示与预发不产生真实争议工单。
     */
    private boolean shouldAutoCompleteEmptyCart() {
        if (systemConfigService.getBoolean(SystemConfigService.SETTLEMENT_EMPTY_AUTO_COMPLETE, false)) {
            return true;
        }
        return securityProperties.mockEnabled() || stagingProperties.stagingMode();
    }

    private static RecognitionOutcome forceReviewIfMockOrMismatch(
            RecognitionOutcome recognition) {
        if (recognition == null || recognition.needReview() || !SettlementService.blocksSilentSettle(recognition)) {
            return recognition;
        }
        return new RecognitionOutcome(
                recognition.taskId(),
                recognition.items(),
                recognition.overallConfidence(),
                true,
                recognition.modelVersion(),
                recognition.detectedClasses()
        );
    }

    /** 争议文案统一走 {@link SettlementDecision}，与矩阵单测同源。 */
    private static String humanReviewReason(RecognitionOutcome recognition) {
        SettlementDecision decision = SettlementDecision.classify(recognition);
        String reason = decision.reviewReason(recognition);
        if (reason != null) {
            return reason;
        }
        return SettlementService.reviewReasonFor(recognition);
    }

    void escalateToDispute(ShoppingSession session,
                           RecognitionOutcome recognition,
                           String reason) {
        // 转人工暂不扣款：释放开门预授权，避免多笔争议会话叠冻结导致可用余额为 0（BUG-001）
        consumerPreauthService.releaseIfFrozen(session);
        disputeService.createTicket(session, recognition, reason);
        throw new DisputeRequiredException(reason);
    }
}
