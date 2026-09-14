package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 会话结算编排：关门后 settle、异步识别回调、演示零元结案、开发上传识别。
 * <p>锁与状态迁移委托 {@link SessionService}；订单/扣款仍由 {@link SettlementService} 幂等保护。</p>
 */
@Service
public class SessionSettleService {
    private static final String BALANCE_INSUFFICIENT = "BALANCE_INSUFFICIENT";
    private static final String LITERAL = "结算余额不足";
    private static final Logger log = LoggerFactory.getLogger(SessionSettleService.class);

    private final ShoppingSessionMapper repository;
    private final SettlementService settlementService;
    private final VisionAsyncProperties visionAsyncProperties;
    private final CabinetMetrics cabinetMetrics;
    private final OpsExceptionService opsExceptionService;
    private final SessionService sessionService;

    public SessionSettleService(ShoppingSessionMapper repository,
                                SettlementService settlementService,
                                VisionAsyncProperties visionAsyncProperties,
                                CabinetMetrics cabinetMetrics,
                                OpsExceptionService opsExceptionService,
                                @Lazy SessionService sessionService) {
        this.repository = repository;
        this.settlementService = settlementService;
        this.visionAsyncProperties = visionAsyncProperties;
        this.cabinetMetrics = cabinetMetrics;
        this.opsExceptionService = opsExceptionService;
        this.sessionService = sessionService;
    }

    /**
     * 关门事务提交后再结算，避免 vision/扣款失败把「已关门」回滚掉。
     * 无外层长事务：分布式锁内调用 settle / 异步投递。
     */
    public SessionDto settleAfterClose(String sessionId) {
        return sessionService.runWithSessionLifeLock(sessionId, () -> {
            ShoppingSession session = repository.findById(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            if (session.getState() != SessionState.RECOGNIZING) {
                return sessionService.toDto(session);
            }
            return settleSession(session);
        });
    }

    SessionDto settleSession(ShoppingSession session) {
        if (session.getState() == SessionState.COMPLETED) {
            return sessionService.toDto(session);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            if (visionAsyncProperties.enabled()) {
                settlementService.submitAsyncRecognition(session);
                return sessionService.toDto(session);
            }
            sessionService.transition(session, SessionState.SETTLING);
        }
        try {
            OrderReadModel order = settlementService.settle(session);
            session.setOrderId(order.orderId());
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("session completed {} order={}", SessionLogContext.of(session), order.orderId());
            cabinetMetrics.recordSettlementSuccess();
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                        new OpsExceptionService.ExceptionReport.ExceptionRefs(
                                session.getDeviceId(), session.getSessionId(), order.orderId(), session.getUserId()),
                        "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            sessionService.transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    "识别结果需人工审核", e.getMessage());
            log.warn("session disputed {}", SessionLogContext.of(session));
            cabinetMetrics.recordSettlementFailure();
            return sessionService.toDto(session);
        } catch (BalanceInsufficientException e) {
            // 兼容旧路径：若结算仍抛余额不足且未落单，则进争议
            session.setFailReason(e.getMessage());
            sessionService.transition(session, SessionState.DISPUTED);
            opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    LITERAL, e.getMessage());
            log.warn("session balance insufficient {}", SessionLogContext.of(session));
            cabinetMetrics.recordSettlementFailure();
            return sessionService.toDto(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                sessionService.transition(session, SessionState.DISPUTED);
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                        new OpsExceptionService.ExceptionReport.ExceptionRefs(
                                session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                        LITERAL, e.getReason());
                return sessionService.toDto(session);
            }
            session.setFailReason(e.getReason());
            sessionService.transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("session failed {} reason={}", SessionLogContext.of(session), e.getReason());
            cabinetMetrics.recordSettlementFailure();
            return sessionService.toDto(session);
        } catch (RestClientException e) {
            log.error("vision/settle remote call failed {}", SessionLogContext.of(session), e);
            opsExceptionService.report("RECOGNITION_UNAVAILABLE", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    "识别或结算服务不可用", e.getMessage());
            sessionService.transition(session, SessionState.FAILED);
            return sessionService.toDto(session);
        } catch (RuntimeException e) {
            log.error("settle failed {}", SessionLogContext.of(session), e);
            cabinetMetrics.recordSettlementFailure();
            opsExceptionService.report("SETTLEMENT_FAILED", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    "订单结算失败", e.getMessage());
            session.setFailReason(ApiMessages.INTERNAL_ERROR);
            if (session.getState().canTransitionTo(SessionState.FAILED)) {
                sessionService.transition(session, SessionState.FAILED);
            }
            repository.save(session);
            return sessionService.toDto(session);
        }
        return sessionService.toDto(session);
    }

    /**
     * 异步识别结果回调：无外层长事务；会话态短事务与结算短事务分离（扣款已 NOT_SUPPORTED）。
     */
    public void completeAsyncRecognition(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        sessionService.runWithSessionLifeLock(sessionId, () -> {
            doCompleteAsyncRecognition(sessionId, recognition);
            return null;
        });
    }

    private void doCompleteAsyncRecognition(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() != SessionState.RECOGNIZING) {
            log.warn("ignore async recognition {} state={}", SessionLogContext.of(session), session.getState());
            return;
        }
        sessionService.transition(session, SessionState.SETTLING);
        try {
            OrderReadModel order = settlementService.processRecognitionResult(session, recognition);
            session.setOrderId(order.orderId());
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("async session completed {} order={}", SessionLogContext.of(session), order.orderId());
            if ("PENDING".equalsIgnoreCase(order.status())) {
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                        new OpsExceptionService.ExceptionReport.ExceptionRefs(
                                session.getDeviceId(), session.getSessionId(), order.orderId(), session.getUserId()),
                        "订单待支付", "余额不足，已生成待支付订单，可催付或关单");
            }
        } catch (DisputeRequiredException e) {
            sessionService.transition(session, SessionState.DISPUTED);
            opsExceptionService.report("RECOGNITION_FAILED", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    "识别结果需人工审核", e.getMessage());
            log.warn("async session disputed {}", SessionLogContext.of(session));
        } catch (BalanceInsufficientException e) {
            session.setFailReason(e.getMessage());
            sessionService.transition(session, SessionState.DISPUTED);
            opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                    LITERAL, e.getMessage());
            log.warn("async session balance insufficient {}", SessionLogContext.of(session));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                sessionService.transition(session, SessionState.DISPUTED);
                log.warn("async session disputed {}", SessionLogContext.of(session));
                return;
            }
            if (e.getStatusCode() == HttpStatus.PRECONDITION_FAILED) {
                session.setFailReason(e.getReason());
                sessionService.transition(session, SessionState.DISPUTED);
                opsExceptionService.report(BALANCE_INSUFFICIENT, "HIGH",
                        new OpsExceptionService.ExceptionReport.ExceptionRefs(
                                session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()),
                        LITERAL, e.getReason());
                log.warn("async session balance insufficient {}", SessionLogContext.of(session));
                return;
            }
            session.setFailReason(e.getReason());
            sessionService.transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("async session failed {} reason={}", SessionLogContext.of(session), e.getReason());
        }
    }

    /** 开发上传识别：用真实 vision 结果结算，不走 mock 兜底。无外层长事务。 */
    public SessionDto completeDevUploadRecognition(String sessionId,
                                                   VisionServiceClient.RecognitionResult recognition) {
        return sessionService.runWithSessionLifeLock(sessionId,
                () -> doCompleteDevUploadRecognition(sessionId, recognition));
    }

    private SessionDto doCompleteDevUploadRecognition(String sessionId,
                                                      VisionServiceClient.RecognitionResult recognition) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (session.getState() == SessionState.SHOPPING) {
            sessionService.transition(session, SessionState.RECOGNIZING);
        }
        if (session.getState() == SessionState.RECOGNIZING) {
            sessionService.transition(session, SessionState.SETTLING);
        }
        try {
            OrderReadModel order = settlementService.processRecognitionResult(session, recognition, false);
            session.setOrderId(order.orderId());
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("dev upload session completed {} order={}", SessionLogContext.of(session), order.orderId());
        } catch (DisputeRequiredException e) {
            sessionService.transition(session, SessionState.DISPUTED);
            log.warn("dev upload session disputed {}", SessionLogContext.of(session));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                sessionService.transition(session, SessionState.DISPUTED);
                log.warn("session disputed {} reason={}", SessionLogContext.of(session), e.getReason());
                cabinetMetrics.recordSettlementFailure();
                return sessionService.toDto(session);
            }
            session.setFailReason(e.getReason());
            sessionService.transition(session, SessionState.FAILED);
            repository.save(session);
            log.warn("dev upload session failed {} reason={}", SessionLogContext.of(session), e.getReason());
        } catch (RuntimeException e) {
            session.setFailReason(ApiMessages.INTERNAL_ERROR);
            if (session.getState().canTransitionTo(SessionState.FAILED)) {
                sessionService.transition(session, SessionState.FAILED);
            }
            repository.save(session);
            log.error("dev upload settle failed {}", SessionLogContext.of(session), e);
        }
        return sessionService.toDto(session);
    }

    /**
     * 演示关门且购物车为空：零元订单并完结会话（与文案「未选则不扣款」一致）。
     */
    @Transactional
    public SessionDto completeDemoZeroSettle(Long userId, String sessionId) {
        return sessionService.runWithSessionLifeLock(sessionId, () -> {
            ShoppingSession session = repository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            if (!session.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND);
            }
            if (session.getState() != SessionState.SHOPPING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不可关门结算");
            }
            log.info("demo-close zero-settle {}", SessionLogContext.of(session));
            sessionService.transition(session, SessionState.RECOGNIZING);
            sessionService.transition(session, SessionState.SETTLING);
            OrderReadModel order = settlementService.settleManual(session, List.of());
            session.setOrderId(order.orderId());
            sessionService.transition(session, SessionState.COMPLETED);
            cabinetMetrics.recordSettlementSuccess();
            return sessionService.toDto(session);
        });
    }
}
