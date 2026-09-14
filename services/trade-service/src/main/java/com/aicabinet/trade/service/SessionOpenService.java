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

    private final ShoppingSessionMapper repository;
    private final UserValidationService userValidationService;
    private final DeviceValidationService deviceValidationService;
    private final MerchantOpsPolicyService opsPolicyService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final UserInfoMapper userInfoRepository;
    private final SessionService sessionService;

    public SessionOpenService(ShoppingSessionMapper repository,
                              UserValidationService userValidationService,
                              DeviceValidationService deviceValidationService,
                              MerchantOpsPolicyService opsPolicyService,
                              ConsumerPreauthService consumerPreauthService,
                              DisplaySnapshotHelper displaySnapshotHelper,
                              UserInfoMapper userInfoRepository,
                              @Lazy SessionService sessionService) {
        this.repository = repository;
        this.userValidationService = userValidationService;
        this.deviceValidationService = deviceValidationService;
        this.opsPolicyService = opsPolicyService;
        this.consumerPreauthService = consumerPreauthService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.userInfoRepository = userInfoRepository;
        this.sessionService = sessionService;
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

    @Transactional
    public void markOpenDoorFailed(String sessionId, String failReason) {
        ShoppingSession session = repository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (session.getState() == SessionState.FAILED || session.getState() == SessionState.CANCELLED
                || session.getState() == SessionState.COMPLETED) {
            return;
        }
        session.setFailReason(failReason);
        sessionService.transition(session, SessionState.FAILED);
        try {
            consumerPreauthService.releaseIfFrozen(session);
        } catch (Exception e) {
            log.warn("release preauth after open fail session={}", sessionId, e);
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
