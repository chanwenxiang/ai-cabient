package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 结算入口编排（从 {@link SettlementService} 拆出）：锁内先判重/校验，
 * 再进入短事务识别落单。识别来源见 {@link #settle}（旧云识别已退役）。
 */
@Service
public class SettlementSettleOrchestrator {

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final DeviceValidationService deviceValidationService;
    private final SettlementRecognitionService settlementRecognitionService;
    private final SettlementService settlement;
    private final SettlementOrderSupport orderSupport;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final SettlementSettleOrchestrator self;

    public SettlementSettleOrchestrator(ShoppingSessionMapper sessionRepository,
                                        CabinetOrderMapper orderRepository,
                                        DeviceValidationService deviceValidationService,
                                        SettlementRecognitionService settlementRecognitionService,
                                        @Lazy SettlementService settlement,
                                        SettlementOrderSupport orderSupport,
                                        @Lazy SettlementSettleOrchestrator self) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.deviceValidationService = deviceValidationService;
        this.settlementRecognitionService = settlementRecognitionService;
        this.settlement = settlement;
        this.orderSupport = orderSupport;
        this.self = self;
    }

    /**
     * 关门结算入口（旧边缘链路残留路径）。
     *
     * <p>🔴 旧云识别已退役（2026-10-11 拍板，docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md）：
     * 此处<b>不再调用任何识别引擎</b>，直接构造「待人工审核」占位结果进既有管道——</p>
     * <ul>
     *   <li>将邑会话根本不走本方法（{@code SessionDoorService} 对将邑豁免 settleAfterClose，
     *       真识别经 edge-results → completeAsyncRecognition 带结果结算）；</li>
     *   <li>生产非将邑会话已被 {@code DeviceValidationService.ensureJiangyiOnlyShopping}
     *       挡在开门（批1），本方法在生产无新增流量；</li>
     *   <li>mock 环境（CI/本地演示）由既有 dev-cart/gravity 证据短路成单（与此前
     *       vision-service 不可达时的熔断回退完全同形，ConsumerE2E 即在该形态下常绿）。</li>
     * </ul>
     * 失败语义保持 fail-closed：无占位之外的识别，一律转人工审核，不扣款。
     */
    public OrderReadModel settle(ShoppingSession session) {
        return settlement.runWithSessionSettleLock(session.getSessionId(), () -> {
            if (orderRepository.findBySessionId(session.getSessionId()).isPresent()) {
                return orderSupport.toDto(orderRepository.findBySessionId(session.getSessionId()).get());
            }
            deviceValidationService.ensureSettlementAllowed(session.getDeviceId());

            VisionServiceClient.RecognitionResult retired = new VisionServiceClient.RecognitionResult(
                    "RETIRED-" + session.getSessionId(), List.of(), 0f, true,
                    "cloud-vision-retired", List.of());
            return self.processRecognitionAfterVision(session, retired);
        });
    }

    /** 识别已完成：短事务内 forUpdate + 落单/扣款。 */
    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionAfterVision(ShoppingSession session,
                                                        VisionServiceClient.RecognitionResult recognition) {
        sessionRepository.findByIdForUpdate(session.getSessionId());
        return settlementRecognitionService.processRecognitionResultUnlocked(session, recognition);
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
    public OrderReadModel processRecognitionResult(ShoppingSession session,
                                                   VisionServiceClient.RecognitionResult recognition,
                                                   boolean allowDevFallback) {
        // 审计批次4：**先抢锁、后开事务**（对齐 BalanceLedgerService 的锁外事务设计）——
        // 原实现 @Transactional 包住 runWithSessionSettleLock，Redis 等锁期间每个请求挂死
        // 一个 DB 连接，10 并发即耗尽连接池。事务边界移入 self 代理的 Tx 方法。
        return settlement.runWithSessionSettleLock(session.getSessionId(), () ->
                self.processRecognitionResultInTx(session, recognition, allowDevFallback));
    }

    @Transactional(noRollbackFor = {DisputeRequiredException.class, BalanceInsufficientException.class})
    public OrderReadModel processRecognitionResultInTx(ShoppingSession session,
                                                       VisionServiceClient.RecognitionResult recognition,
                                                       boolean allowDevFallback) {
        sessionRepository.findByIdForUpdate(session.getSessionId());
        return settlementRecognitionService.processRecognitionResultUnlocked(
                session, recognition, allowDevFallback);
    }
}
