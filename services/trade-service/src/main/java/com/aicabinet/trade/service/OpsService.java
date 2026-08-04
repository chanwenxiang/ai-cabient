package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OpsService {

    private final SessionService sessionService;
    private final DeviceValidationService deviceValidationService;
    private final DeviceServiceClient deviceClient;
    private final ShoppingSessionMapper sessionRepository;
    private final ReplenishmentTaskMapper taskRepository;

    public OpsService(SessionService sessionService,
                      DeviceValidationService deviceValidationService,
                      DeviceServiceClient deviceClient,
                      ShoppingSessionMapper sessionRepository,
                      ReplenishmentTaskMapper taskRepository) {
        this.sessionService = sessionService;
        this.deviceValidationService = deviceValidationService;
        this.deviceClient = deviceClient;
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
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
        deviceValidationService.requireDevice(deviceId);
        ReplenishmentTask task = deviceValidationService.ensureRestockDoorAllowed(deviceId, taskId, userId);

        ShoppingSession session = new ShoppingSession();
        String sessionId = generateSessionId();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setReplenishmentTaskId(task.getTaskId());
        session.setIdempotencyKey("RESTOCK:" + task.getTaskId() + ":" + sessionId);
        session.setState(com.aicabinet.common.enums.SessionState.OPENING);
        sessionRepository.save(session);

        if (!"IN_PROGRESS".equals(task.getStatus())) {
            task.setStatus("IN_PROGRESS");
            taskRepository.save(task);
        }

        deviceClient.requestOpenDoorOperator(session.getSessionId(), deviceId, userId);
        return sessionService.getSession(userId, session.getSessionId());
    }

    private void requireOperator(Long userId) {
        OperatorAuth.requireOperator(userId);
    }

    private String generateSessionId() {
        return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
