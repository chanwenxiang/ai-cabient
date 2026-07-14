package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.repository.ReplenishmentTaskRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OpsService {

    private final SessionService sessionService;
    private final DeviceValidationService deviceValidationService;
    private final DeviceServiceClient deviceClient;
    private final ShoppingSessionRepository sessionRepository;
    private final ReplenishmentTaskRepository taskRepository;

    public OpsService(SessionService sessionService,
                      DeviceValidationService deviceValidationService,
                      DeviceServiceClient deviceClient,
                      ShoppingSessionRepository sessionRepository,
                      ReplenishmentTaskRepository taskRepository) {
        this.sessionService = sessionService;
        this.deviceValidationService = deviceValidationService;
        this.deviceClient = deviceClient;
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
    }

    /** 运营补货开门：绑定补货任务，不校验余额，不触发消费者结算。 */
    @Transactional
    public SessionDto openDoorForRestock(Long operatorUserId, OpsOpenDoorRequest request) {
        requireOperator(operatorUserId);
        deviceValidationService.requireDevice(request.deviceId());
        ReplenishmentTask task = deviceValidationService.ensureRestockDoorAllowed(
                request.deviceId(), request.taskId(), operatorUserId);

        ShoppingSession session = new ShoppingSession();
        String sessionId = generateSessionId();
        session.setSessionId(sessionId);
        session.setUserId(operatorUserId);
        session.setDeviceId(request.deviceId());
        session.setReplenishmentTaskId(task.getTaskId());
        session.setIdempotencyKey("RESTOCK:" + task.getTaskId() + ":" + sessionId);
        session.setState(com.aicabinet.common.enums.SessionState.OPENING);
        sessionRepository.save(session);

        if (!"IN_PROGRESS".equals(task.getStatus())) {
            task.setStatus("IN_PROGRESS");
            taskRepository.save(task);
        }

        deviceClient.requestOpenDoorOperator(session.getSessionId(), request.deviceId(), operatorUserId);
        return sessionService.getSession(operatorUserId, session.getSessionId());
    }

    private void requireOperator(Long userId) {
        OperatorAuth.requireOperator(userId);
    }

    private String generateSessionId() {
        return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
