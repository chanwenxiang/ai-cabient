package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.util.BizIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费者开门短事务：会话落库与开门失败回滚预授权。
 * <p>状态迁移与 DTO 映射委托 {@link SessionService}，避免行为漂移。</p>
 */
@Service
public class SessionOpenService {
    private static final Logger log = LoggerFactory.getLogger(SessionOpenService.class);
    /** C10：开门失败释放预授权失败时的运营告警类型。 */
    static final String ALERT_OPEN_DOOR_PREAUTH_RELEASE_FAILED = "OPEN_DOOR_PREAUTH_RELEASE_FAILED";

    private final ShoppingSessionMapper repository;
    private final UserValidationService userValidationService;
    private final DeviceValidationService deviceValidationService;
    private final MerchantOpsPolicyService opsPolicyService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final UserInfoMapper userInfoRepository;
    private final SessionService sessionService;
    private final DistributedLockService distributedLockService;
    private final OpsAlertDispatcher opsAlertDispatcher;

    public SessionOpenService(ShoppingSessionMapper repository,
                              UserValidationService userValidationService,
                              DeviceValidationService deviceValidationService,
                              MerchantOpsPolicyService opsPolicyService,
                              ConsumerPreauthService consumerPreauthService,
                              DisplaySnapshotHelper displaySnapshotHelper,
                              UserInfoMapper userInfoRepository,
                              @Lazy SessionService sessionService,
                              DistributedLockService distributedLockService,
                              OpsAlertDispatcher opsAlertDispatcher) {
        this.repository = repository;
        this.userValidationService = userValidationService;
        this.deviceValidationService = deviceValidationService;
        this.opsPolicyService = opsPolicyService;
        this.consumerPreauthService = consumerPreauthService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.userInfoRepository = userInfoRepository;
        this.sessionService = sessionService;
        this.distributedLockService = distributedLockService;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    @Transactional
    public SessionDto persistConsumerOpeningSession(Long userId, CreateSessionRequest request) {
        String entryChannel = resolveEntryChannel(userId, request.entryChannel());
        userValidationService.validateCanOpenDoor(userId, request.deviceId(), entryChannel);
        deviceValidationService.requireDevice(request.deviceId());
        deviceValidationService.ensureDeviceAvailable(request.deviceId());
        opsPolicyService.requireInflightCapacity(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(BizIds.nextNumeric());
        session.setUserId(userId);
        session.setDeviceId(request.deviceId());
        displaySnapshotHelper.applySessionDeviceName(session);
        session.setState(SessionState.CREATED);
        session.setEntryChannel(entryChannel);
        session.setPreferredCouponId(request.preferredCouponId());
        session.setIdempotencyKey(normalizeIdempotencyKey(request.idempotencyKey()));
        // 先强制写入唯一幂等键，再改变状态和下发开门命令。并发重复请求会在这里失败，
        // 不会出现两个事务都先向同一台柜机发送开门命令、最后才在提交时发现冲突。
        repository.saveAndFlush(session);

        boolean passwordFree = userValidationService.isPasswordFreeReady(userId, entryChannel);
        consumerPreauthService.freezeForOpen(session, passwordFree);

        sessionService.transition(session, SessionState.OPENING);
        return sessionService.toDto(session);
    }

    /**
     * 开门失败标记（C10）：与会话清理路径一致——先抢 session:life 分布式锁 + findByIdForUpdate
     * 行锁重查，transition 前做状态 CAS；抢锁失败说明有并发迁移在处理，跳过交给超时清扫兜底。
     */
    @Transactional
    public void markOpenDoorFailed(String sessionId, String failReason) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.warn("mark open door failed skipped busy session={}", sessionId);
            return;
        }
        try {
            ShoppingSession session = repository.findByIdForUpdate(sessionId).orElse(null);
            if (session == null) {
                return;
            }
            // 状态 CAS：终态（FAILED/CANCELLED/COMPLETED）不再迁移
            if (session.getState() == SessionState.FAILED || session.getState() == SessionState.CANCELLED
                    || session.getState() == SessionState.COMPLETED) {
                return;
            }
            session.setFailReason(failReason);
            sessionService.transition(session, SessionState.FAILED);
            try {
                consumerPreauthService.releaseIfFrozen(session);
            } catch (Exception e) {
                log.error("release preauth after open fail session={}", sessionId, e);
                try {
                    opsAlertDispatcher.send(ALERT_OPEN_DOOR_PREAUTH_RELEASE_FAILED,
                            "开门失败后预授权释放失败",
                            "sessionId=" + sessionId + " device=" + session.getDeviceId()
                                    + " userId=" + session.getUserId() + "，需人工核对预授权",
                            java.util.Map.of("sessionId", sessionId,
                                    "deviceId", String.valueOf(session.getDeviceId())));
                } catch (Exception alertEx) {
                    log.error("ops alert failed type={} session={}",
                            ALERT_OPEN_DOOR_PREAUTH_RELEASE_FAILED, sessionId, alertEx);
                }
            }
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }

    private String resolveEntryChannel(Long userId, String requested) {
        String entry = PayChannels.normalizeEntryChannel(requested);
        if (entry != null) {
            return entry;
        }
        return userInfoRepository.findById(userId)
                .map(u -> PayChannels.normalizeEntryChannel(u.getPayPreferredChannel()))
                .orElse(null);
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        return key.trim();
    }
}
