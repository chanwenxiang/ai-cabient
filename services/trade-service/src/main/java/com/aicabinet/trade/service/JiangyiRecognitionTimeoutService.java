package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 将邑识别超时即时处置（CB-022）：gateway 在「识别上报到达但 class 映射未命中」
 * 时经内部接口触发，立即把 RECOGNIZING 会话转人工审核，不等 10 分钟扫描兜底。
 *
 * <p>状态迁移语义对齐既有识别超时路径（SessionExpireService.upgradeConsumerRecognizingTimeout，
 * C09）：释放预授权冻结 → 转 DISPUTED → 开超时争议单；DISPUTED 不可达则回退 FAILED。
 * 补货会话（isRestockSession）不在此处置——其超时语义不同（转 COMPLETED 快照关闭），
 * 交给既有扫描兜底，本服务直接跳过。</p>
 *
 * <p>幂等：加会话锁 + 行锁后校验 state==RECOGNIZING 才动作；非 RECOGNIZING
 * （已结算/已取消/已争议）一律返回 false 不重复处置。</p>
 */
@Service
public class JiangyiRecognitionTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiRecognitionTimeoutService.class);

    private final ShoppingSessionMapper repository;
    private final SessionService sessionService;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DistributedLockService distributedLockService;
    private final OpsExceptionService opsExceptionService;

    public JiangyiRecognitionTimeoutService(ShoppingSessionMapper repository,
                                            @Lazy SessionService sessionService,
                                            @Lazy DisputeService disputeService,
                                            ConsumerPreauthService consumerPreauthService,
                                            DistributedLockService distributedLockService,
                                            OpsExceptionService opsExceptionService) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
        this.distributedLockService = distributedLockService;
        this.opsExceptionService = opsExceptionService;
    }

    /**
     * @return true = 本次由本服务完成升级（转 DISPUTED/FAILED）；
     *         false = 会话不存在 / 不在 RECOGNIZING / 补货会话（无需或不应处置）。
     */
    public boolean markRecognitionTimeout(String sessionId) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.debug("jiangyi recognize-timeout skipped busy session={}", sessionId);
            return false;
        }
        try {
            ShoppingSession locked = repository.findByIdForUpdate(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            if (locked.getState() != SessionState.RECOGNIZING) {
                // 幂等：已结算/已取消/已争议等一律不重复处置
                return false;
            }
            if (DeviceValidationService.isRestockSession(locked)) {
                log.info("jiangyi recognize-timeout skipped restock session {}",
                        SessionLogContext.of(locked));
                return false;
            }
            String failReason = "识别结果无法匹配商品（class 映射未命中），已转人工审核，本次暂未扣款";
            // C09：升级前释放预授权冻结；失败只记 error 并告警，不阻断状态迁移
            try {
                consumerPreauthService.releaseIfFrozen(locked);
            } catch (Exception e) {
                log.error("将邑识别超时释放预授权失败 sessionId={}", locked.getSessionId(), e);
            }
            if (locked.getState().canTransitionTo(SessionState.DISPUTED)) {
                locked.setFailReason(failReason);
                if (locked.getCloseTime() == null) {
                    locked.setCloseTime(Instant.now());
                }
                sessionService.transition(locked, SessionState.DISPUTED);
                try {
                    disputeService.createTimeoutTicket(locked, failReason);
                } catch (Exception ticketEx) {
                    log.warn("将邑超时争议单创建失败 {}", SessionLogContext.of(locked), ticketEx);
                }
            } else {
                locked.setFailReason("识别结果无法匹配商品（class 映射未命中）");
                sessionService.transition(locked, SessionState.FAILED);
            }
            opsExceptionService.report(
                    "JIANGYI_CLASS_MAPPING_MISS",
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            locked.getDeviceId(), locked.getSessionId(),
                            locked.getOrderId(), locked.getUserId()),
                    "将邑识别映射未命中",
                    "识别上报的 classId 无法解析到我方 SKU，会话已 fail-closed 转人工审核");
            log.warn("将邑识别超时会话已升级 {} state={}", SessionLogContext.of(locked), locked.getState());
            return true;
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }
}
