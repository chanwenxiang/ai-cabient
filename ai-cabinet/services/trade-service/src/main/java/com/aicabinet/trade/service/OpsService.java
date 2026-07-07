package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
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

    public OpsService(SessionService sessionService,
                      DeviceValidationService deviceValidationService,
                      DeviceServiceClient deviceClient,
                      ShoppingSessionRepository sessionRepository) {
        this.sessionService = sessionService;
        this.deviceValidationService = deviceValidationService;
        this.deviceClient = deviceClient;
        this.sessionRepository = sessionRepository;
    }

    /** 运营补货开门：不校验余额，不触发结算 */
    @Transactional
    public SessionDto openDoorForRestock(Long operatorUserId, OpsOpenDoorRequest request) {
        requireOperator(operatorUserId);
        deviceValidationService.requireDevice(request.deviceId());

        ShoppingSession session = new ShoppingSession();
        session.setSessionId(generateSessionId());
        session.setUserId(operatorUserId);
        session.setDeviceId(request.deviceId());
        session.setState(com.aicabinet.common.enums.SessionState.OPENING);
        sessionRepository.save(session);

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
