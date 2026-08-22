package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OpsService {

    private final SessionService sessionService;
    private final DeviceValidationService deviceValidationService;
    private final DeviceServiceClient deviceClient;
    private final ShoppingSessionMapper sessionRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final DistributedLockService distributedLockService;

    public OpsService(SessionService sessionService,
                      DeviceValidationService deviceValidationService,
                      DeviceServiceClient deviceClient,
                      ShoppingSessionMapper sessionRepository,
                      ReplenishmentTaskMapper taskRepository,
                      DistributedLockService distributedLockService) {
        this.sessionService = sessionService;
        this.deviceValidationService = deviceValidationService;
        this.deviceClient = deviceClient;
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
        this.distributedLockService = distributedLockService;
    }

    /** 运营账号补货开门（需 userId ≥ 100000000）。 */
    @Transactional
    public SessionDto openDoorForRestock(Long operatorUserId, OpsOpenDoorRequest request) {
        requireOperator(operatorUserId);
        return openDoorForRestockAsUser(operatorUserId, request.deviceId(), request.taskId());
    }

    /**
     * 补货开门核心逻辑（运营 / 商户补货员共用）：
     * 绑定补货任务，不校验消费者余额，不触发购物结算。
     */
    @Transactional
    public SessionDto openDoorForRestockAsUser(Long userId, String deviceId, Long taskId) {
        return runWithReplenishmentLock(taskId, () -> doOpenDoorForRestockAsUser(userId, deviceId, taskId));
    }

    private SessionDto doOpenDoorForRestockAsUser(Long userId, String deviceId, Long taskId) {
        deviceValidationService.requireDevice(deviceId);
        ReplenishmentTask task = deviceValidationService.ensureRestockDoorAllowed(deviceId, taskId, userId);
        ReplenishmentTask lockedTask = taskRepository.findByIdForUpdate(task.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));

        ShoppingSession session = new ShoppingSession();
        String sessionId = generateSessionId();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setReplenishmentTaskId(lockedTask.getTaskId());
        session.setIdempotencyKey("RESTOCK:" + lockedTask.getTaskId() + ":" + sessionId);
        session.setState(com.aicabinet.common.enums.SessionState.OPENING);
        sessionRepository.save(session);

        if (!"IN_PROGRESS".equals(lockedTask.getStatus())) {
            lockedTask.setStatus("IN_PROGRESS");
            taskRepository.save(lockedTask);
        }

        deviceClient.requestOpenDoorOperator(session.getSessionId(), deviceId, userId);
        return sessionService.getSession(userId, session.getSessionId());
    }

    private <T> T runWithReplenishmentLock(Long taskId, java.util.function.Supplier<T> action) {
        String key = ReplenishmentService.replenishmentTaskLockKey(taskId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "补货任务处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private void requireOperator(Long userId) {
        OperatorAuth.requireOperator(userId);
    }

    private String generateSessionId() {
        return BizIds.nextNumeric();
    }
}
