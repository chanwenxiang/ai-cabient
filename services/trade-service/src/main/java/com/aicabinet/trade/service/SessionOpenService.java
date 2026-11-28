package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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
    private final SessionService sessionService;
    private final DistributedLockService distributedLockService;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final PayScoreService payScoreService;

    public SessionOpenService(ShoppingSessionMapper repository,
                              UserValidationService userValidationService,
                              DeviceValidationService deviceValidationService,
                              MerchantOpsPolicyService opsPolicyService,
                              ConsumerPreauthService consumerPreauthService,
                              DisplaySnapshotHelper displaySnapshotHelper,
                              @Lazy SessionService sessionService,
                              DistributedLockService distributedLockService,
                              OpsAlertDispatcher opsAlertDispatcher,
                              PayScoreService payScoreService) {
        this.repository = repository;
        this.userValidationService = userValidationService;
        this.deviceValidationService = deviceValidationService;
        this.opsPolicyService = opsPolicyService;
        this.consumerPreauthService = consumerPreauthService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.sessionService = sessionService;
        this.distributedLockService = distributedLockService;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.payScoreService = payScoreService;
    }

    @Transactional
    public SessionDto persistConsumerOpeningSession(Long userId, CreateSessionRequest request) {
        String entryChannel = resolveEntryChannel(request.entryChannel());
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

        // F3b：冻结与否的唯一判据是「本次会话是否真的创建了微信支付分单」，不再是扫码渠道的
        // 「免密是否就绪」。此前按 entryChannel 判：用户只有支付宝签约、从支付宝码进来时
        // passwordFree=true ⇒ 不冻余额；但 openServiceOrderForShopping 只认微信支付分，不会建单，
        // 结算回落余额时账户里一分钱没冻 ⇒ 应收落空。先建单再读「单在不在」，判据与结算侧
        // OrderPaymentService#shouldCompleteWeChatPayScore 完全同源（同一张 payscore_order 表）。
        // 注意：openServiceOrderForShopping 失败会抛 412，整个开门事务回滚，不存在「建单失败却跳过冻结」。
        payScoreService.openServiceOrderForShopping(session,
                consumerPreauthService.resolvePreauthCents(request.deviceId()));
        boolean passwordFree = payScoreService.hasOpenWechatOrderForSession(session.getSessionId());
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
        markOpenDoorFailed(sessionId, failReason, null);
    }

    /** AU-060：reportingDeviceId 非空时须与会话绑定设备一致（将邑转发链透传 token deviceId）。 */
    @Transactional
    public void markOpenDoorFailed(String sessionId, String failReason, String reportingDeviceId) {
        markOpenDoorFailedInternal(sessionId, failReason, reportingDeviceId, false);
    }

    /**
     * 设备明确上报“拉门失败”。若此前只有解锁 ACK 把消费会话救到 SHOPPING，
     * 该信号可撤销假开门；已确认物理拉门的会话仍忽略迟到的失败信号。
     */
    @Transactional
    public void markPullFailed(String sessionId, String failReason, String reportingDeviceId) {
        markOpenDoorFailedInternal(sessionId, failReason, reportingDeviceId, true);
    }

    private void markOpenDoorFailedInternal(String sessionId, String failReason,
                                            String reportingDeviceId, boolean explicitPullFailure) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.warn("mark open door failed skipped busy session={}", sessionId);
            return;
        }
        try {
            ShoppingSession session = repository.findByIdForUpdate(sessionId).orElse(null);
            if (session == null) {
                return;
            }
            // AU-060：设备上报面归属校验——转发链带 deviceId 时，非会话绑定设备的
            // open-failed 一律拒绝（对齐 door-event 的 DEVICE_MISMATCH 口径）。
            if (reportingDeviceId != null && !reportingDeviceId.equals(session.getDeviceId())) {
                log.warn("mark open door failed DEVICE_MISMATCH session={} bound={} reported={}",
                        sessionId, session.getDeviceId(), reportingDeviceId);
                return;
            }
            boolean earlyState = session.getState() == SessionState.CREATED
                    || session.getState() == SessionState.OPENING;
            boolean pullFailedAfterUnlockOnly = explicitPullFailure
                    && session.getState() == SessionState.SHOPPING
                    && !session.isPhysicalOpenConfirmed()
                    && !DeviceValidationService.isNonConsumerSession(session);
            // 普通 ACK 超时仍只允许早期态，避免误杀已物理开门/识别中的会话；
            // 只有设备明确 pull-fail 才可回收“仅解锁未拉门”的救援会话。
            if (!earlyState && !pullFailedAfterUnlockOnly) {
                log.info("mark open door failed skipped state={} session={} physicalOpenConfirmed={}",
                        session.getState(), sessionId, session.isPhysicalOpenConfirmed());
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

    /** 入口渠道只认扫码/显式传入；不再回落到用户持久偏好（2026-11-17 拍板移除偏好）。 */
    private String resolveEntryChannel(String requested) {
        return PayChannels.normalizeEntryChannel(requested);
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        return key.trim();
    }
}
