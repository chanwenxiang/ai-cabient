package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
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
 * 识别结果决策管线（从 {@link SettlementService} 拆出）：重力兜底、审单旁路、空购零结、争议升级。
 * 落单/扣款与 DTO 仍委托 {@link SettlementService}。
 */
@Service
public class SettlementRecognitionService {

    private static final String GRAVITY_MISMATCH = "gravity-mismatch";
    private static final String GRAVITY_FILL = "gravity-fill";

    private static final Logger log = LoggerFactory.getLogger(SettlementRecognitionService.class);

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final SettlementConfidenceService confidenceService;
    private final GravitySettlementHelper gravityHelper;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;
    private final SystemConfigService systemConfigService;
    private final SkuVisionEnrollmentService skuVisionEnrollmentService;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;
    private final SettlementService settlement;

    public SettlementRecognitionService(ShoppingSessionMapper sessionRepository,
                                        CabinetOrderMapper orderRepository,
                                        SettlementConfidenceService confidenceService,
                                        GravitySettlementHelper gravityHelper,
                                        SecurityProperties securityProperties,
                                        StagingProperties stagingProperties,
                                        SystemConfigService systemConfigService,
                                        SkuVisionEnrollmentService skuVisionEnrollmentService,
                                        @Lazy DisputeService disputeService,
                                        ConsumerPreauthService consumerPreauthService,
                                        @Lazy SettlementService settlement) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.confidenceService = confidenceService;
        this.gravityHelper = gravityHelper;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
        this.systemConfigService = systemConfigService;
        this.skuVisionEnrollmentService = skuVisionEnrollmentService;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
        this.settlement = settlement;
    }

    OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult recognition) {
        return processRecognitionResultUnlocked(session, recognition, true);
    }

    OrderReadModel processRecognitionResultUnlocked(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult recognition,
                                                    boolean allowDevFallback) {
        var existingOrder = orderRepository.findBySessionId(session.getSessionId());
        if (existingOrder.isPresent()) {
            return settlement.toDto(existingOrder.get());
        }

        session.setRecognitionTaskId(recognition.taskId());
        sessionRepository.save(session);

        recognition = withGravityFallback(session, recognition);
        recognition = forceReviewIfMockOrMismatch(recognition);
        if (recognition == null) {
            throw new IllegalStateException("recognition result is null after gravity/review normalize");
        }

        OrderReadModel early = trySettleWhenReviewRequired(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }
        early = trySettleDevMock(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }
        early = trySettleEmptyRecognition(session, recognition, allowDevFallback);
        if (early != null) {
            return early;
        }

        String confidenceReason = confidenceService.reviewReasonIfNeeded(recognition);
        if (confidenceReason != null) {
            OrderReadModel stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
            if (systemConfigService.getBoolean(SystemConfigService.DISPUTE_AUTO_OPEN, true)) {
                escalateToDispute(session, recognition, confidenceReason);
            } else {
                log.info("dispute.auto_open=false, settle despite low confidence session={} reason={}",
                        session.getSessionId(), confidenceReason);
            }
        }

        var whitelistReason = skuVisionEnrollmentService.validateSettlementItems(
                session.getDeviceId(), recognition.items());
        if (whitelistReason.isPresent()) {
            escalateToDispute(session, recognition, whitelistReason.get());
        }

        return settlement.finalizeOrder(session, recognition.items());
    }

    /**
     * 视觉显式 need_review：沙箱 gravity-fill / 本地 mock 重力证据可静默结算，否则进审单。
     * @return 已结算订单，或 null 表示未拦截（继续后续路径）
     */
    private OrderReadModel trySettleWhenReviewRequired(ShoppingSession session,
                                                       VisionServiceClient.RecognitionResult recognition,
                                                       boolean allowDevFallback) {
        if (!recognition.needReview()) {
            return null;
        }
        // 沙箱：gravity-fill（视觉空+重力有货）允许按重力结算；错配仍禁止静默扣款
        if (!SettlementService.blocksSilentSettle(recognition) || allowsSandboxGravityFillSettle(recognition)) {
            OrderReadModel stagingOrder = tryStagingGravitySettle(session);
            if (stagingOrder != null) {
                return stagingOrder;
            }
        }
        // 本地 mock：有重力扣减证据时按购物车结算（OBS-012）；纯 mock 无证据仍进审单
        if (allowDevFallback) {
            OrderReadModel cartOrder = tryDevMockEvidenceSettle(session, recognition);
            if (cartOrder != null) {
                return cartOrder;
            }
        }
        escalateToDispute(session, recognition, humanReviewReason(recognition));
        return null;
    }

    /**
     * 本地 mock 自动结算路径：标称结果不可当作生产精度，有重力证据或沙箱 gravity-fill 除外。
     */
    private OrderReadModel trySettleDevMock(ShoppingSession session,
                                            VisionServiceClient.RecognitionResult recognition,
                                            boolean allowDevFallback) {
        if (!allowDevFallback || !securityProperties.mockEnabled()) {
            return null;
        }
        if (SettlementService.blocksSilentSettle(recognition)) {
            if (allowsSandboxGravityFillSettle(recognition)) {
                OrderReadModel stagingOrder = tryStagingGravitySettle(session);
                if (stagingOrder != null) {
                    return stagingOrder;
                }
            }
            OrderReadModel cartOrder = tryDevMockEvidenceSettle(session, recognition);
            if (cartOrder != null) {
                return cartOrder;
            }
            escalateToDispute(session, recognition, humanReviewReason(recognition));
            return null;
        }
        List<VisionServiceClient.RecognizedItem> cartItems = List.of();
        if (allowsGravityEvidenceSettle()) {
            cartItems = gravityHelper.toRecognizedItems(session.getGravityDeltas());
        }
        if (cartItems.isEmpty() && recognition.items() != null && !recognition.items().isEmpty()) {
            cartItems = recognition.items();
            log.info("dev mock settle session={} using vision items={}", session.getSessionId(), cartItems.size());
        } else {
            log.info("dev mock settle session={} cartItems={}", session.getSessionId(), cartItems.size());
        }
        return settlement.finalizeOrder(session, cartItems);
    }

    /** 视觉空结果：重力零结 / mock 零结 / 进审单。 */
    private OrderReadModel trySettleEmptyRecognition(ShoppingSession session,
                                                     VisionServiceClient.RecognitionResult recognition,
                                                     boolean allowDevFallback) {
        if (!recognition.items().isEmpty()) {
            return null;
        }
        OrderReadModel stagingOrder = tryStagingGravitySettle(session);
        if (stagingOrder != null) {
            return stagingOrder;
        }
        // 竞品口径：视觉空 + 重力净零（含显式 []）→ 未取货/拿了又放回，自动零结；
        // 无重力证据时生产仍进争议（防摄像头静默失败）。
        if (shouldAutoCompleteEmptyCart(session)) {
            log.info("empty cart auto-complete session={} device={} gravityPresent={}",
                    session.getSessionId(), session.getDeviceId(),
                    session.getGravityDeltas() != null);
            return settlement.finalizeOrder(session, List.of());
        }
        if (allowDevFallback && securityProperties.mockEnabled()) {
            log.warn("dev mock: empty recognition, zero-settle session={}", session.getSessionId());
            return settlement.finalizeOrder(session, List.of());
        }
        escalateToDispute(session, recognition, "未识别到商品，需人工审核");
        return null;
    }

    /**
     * 视觉无商品时是否自动零结。
     * <ul>
     *   <li>VISION_GRAVITY：重力净扣减为空且会话带重力字段（含 {@code []}）→ 传感器佐证未取货</li>
     *   <li>VISION / 无重力字段 → 看 empty_auto_complete_no_gravity 或 mock/staging，否则进审单</li>
     * </ul>
     */
    private boolean shouldAutoCompleteEmptyCart(ShoppingSession session) {
        if (systemConfigService.usesGravityFusion()) {
            List<VisionServiceClient.RecognizedItem> gravityItems =
                    gravityHelper.toRecognizedItems(session.getGravityDeltas());
            if (gravityItems != null && !gravityItems.isEmpty()) {
                return false;
            }
            if (session.getGravityDeltas() != null) {
                return true;
            }
        }
        if (systemConfigService.getBoolean(SystemConfigService.SETTLEMENT_EMPTY_AUTO_NO_GRAVITY, false)) {
            return true;
        }
        return securityProperties.mockEnabled()
                || stagingProperties.stagingMode()
                || stagingProperties.gravityFallbackSettle();
    }

    private boolean allowsGravityEvidenceSettle() {
        return systemConfigService.usesGravityFusion() || stagingProperties.gravityFallbackSettle();
    }

    /** 预发/沙箱 E2E：有重力扣减信号时优先按重力结算，避免无真实购物视频时误入争议。 */
    private OrderReadModel tryStagingGravitySettle(ShoppingSession session) {
        if (!allowsGravityEvidenceSettle()) {
            return null;
        }
        if (!stagingProperties.stagingMode() && !stagingProperties.gravityFallbackSettle()) {
            return null;
        }
        List<VisionServiceClient.RecognizedItem> gravityItems =
                gravityHelper.toRecognizedItems(session.getGravityDeltas());
        if (gravityItems.isEmpty()) {
            return null;
        }
        log.info("staging gravity settle session={} items={}", session.getSessionId(), gravityItems.size());
        return settlement.finalizeOrder(session, gravityItems);
    }

    /**
     * 沙箱重力兜底仅放行「视觉为空、重力补全」(gravity-fill)。
     * gravity-mismatch / 纯 mock 仍须人工审核。
     */
    private boolean allowsSandboxGravityFillSettle(VisionServiceClient.RecognitionResult recognition) {
        if (!systemConfigService.usesGravityFusion()) {
            return false;
        }
        if (!stagingProperties.stagingMode() && !stagingProperties.gravityFallbackSettle()) {
            return false;
        }
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        return version.contains(GRAVITY_FILL) && !version.contains(GRAVITY_MISMATCH);
    }

    /**
     * 本地演示：{@code AICABINET_MOCK_ENABLED} 下，只要会话有重力扣减证据，即按重力 finalize → PAID。
     * 覆盖 mock-v1 / gravity-fill / gravity-mismatch（演示柜模拟器常带重力，视觉 mock 易错配）。
     * 生产（mock 关闭）不走此分支；无重力证据返回 null，由调用方进审单。
     */
    private OrderReadModel tryDevMockEvidenceSettle(ShoppingSession session,
                                                    VisionServiceClient.RecognitionResult recognition) {
        if (!allowsGravityEvidenceSettle()) {
            return null;
        }
        if (!securityProperties.mockEnabled()) {
            return null;
        }
        List<VisionServiceClient.RecognizedItem> cartItems =
                gravityHelper.toRecognizedItems(session.getGravityDeltas());
        if (cartItems == null || cartItems.isEmpty()) {
            return null;
        }
        log.info("dev mock settle with gravity evidence session={} cartItems={} version={}",
                session.getSessionId(), cartItems.size(), recognition.modelVersion());
        return settlement.finalizeOrder(session, cartItems);
    }

    VisionServiceClient.RecognitionResult withGravityFallback(ShoppingSession session,
                                                              VisionServiceClient.RecognitionResult recognition) {
        if (!systemConfigService.usesGravityFusion()) {
            return recognition;
        }
        return gravityHelper.reconcileWithGravity(session.getGravityDeltas(), recognition);
    }

    private static VisionServiceClient.RecognitionResult forceReviewIfMockOrMismatch(
            VisionServiceClient.RecognitionResult recognition) {
        if (recognition == null || recognition.needReview() || !SettlementService.blocksSilentSettle(recognition)) {
            return recognition;
        }
        return new VisionServiceClient.RecognitionResult(
                recognition.taskId(),
                recognition.items(),
                recognition.overallConfidence(),
                true,
                recognition.modelVersion(),
                recognition.detectedClasses()
        );
    }

    /** 争议文案统一走 {@link SettlementDecision}，与矩阵单测同源。 */
    private static String humanReviewReason(VisionServiceClient.RecognitionResult recognition) {
        SettlementDecision decision = SettlementDecision.classify(recognition);
        String reason = decision.reviewReason(recognition);
        if (reason != null) {
            return reason;
        }
        return SettlementService.reviewReasonFor(recognition);
    }

    void escalateToDispute(ShoppingSession session,
                           VisionServiceClient.RecognitionResult recognition,
                           String reason) {
        // 转人工暂不扣款：释放开门预授权，避免多笔争议会话叠冻结导致可用余额为 0（BUG-001）
        consumerPreauthService.releaseIfFrozen(session);
        disputeService.createTicket(session, recognition, reason);
        throw new DisputeRequiredException(reason);
    }
}
