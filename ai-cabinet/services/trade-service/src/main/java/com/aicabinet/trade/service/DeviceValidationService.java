package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.ReplenishmentTaskRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class DeviceValidationService {

    private static final List<SessionState> BLOCKING_SESSION_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING, SessionState.RECOGNIZING,
            SessionState.SETTLING
    );

    private static final List<SessionState> ACTIVE_SESSION_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING, SessionState.RECOGNIZING
    );

    private final DeviceInfoRepository deviceInfoRepository;
    private final ShoppingSessionRepository sessionRepository;
    private final ReplenishmentTaskRepository replenishmentTaskRepository;

    public DeviceValidationService(DeviceInfoRepository deviceInfoRepository,
                                   ShoppingSessionRepository sessionRepository,
                                   ReplenishmentTaskRepository replenishmentTaskRepository) {
        this.deviceInfoRepository = deviceInfoRepository;
        this.sessionRepository = sessionRepository;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
    }

    public DeviceInfo requireDevice(String deviceId) {
        return deviceInfoRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
    }

    public DeviceStatusDto getDeviceStatus(String deviceId) {
        DeviceInfo device = requireDevice(deviceId);
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_SESSION_STATES);
        String activeSessionId = active.isEmpty() ? null : active.get(0).getSessionId();
        boolean online = "ONLINE".equalsIgnoreCase(device.getOnlineStatus());
        return new DeviceStatusDto(
                device.getDeviceId(),
                device.getDeviceName(),
                device.getOnlineStatus(),
                online,
                active.isEmpty() && !hasInProgressReplenishmentTask(deviceId),
                activeSessionId
        );
    }

    /** 消费者开门：无占用会话，且无进行中的补货任务。 */
    public void ensureDeviceAvailable(String deviceId) {
        ensureConsumerShoppingAllowed(deviceId);
    }

    public void ensureConsumerShoppingAllowed(String deviceId) {
        ensureNoBlockingSession(deviceId);
        if (hasInProgressReplenishmentTask(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_BUSY);
        }
    }

    /** 补货开门：校验任务归属/状态，允许同一任务在门关闭后再次开门。 */
    public ReplenishmentTask ensureRestockDoorAllowed(String deviceId, Long taskId, Long operatorUserId) {
        ReplenishmentTask task = replenishmentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));
        if (!task.getDeviceId().equals(deviceId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.REPLENISHMENT_TASK_MISMATCH);
        }
        if ("COMPLETED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_FINISHED);
        }
        if (task.getAssigneeUserId() != null && !task.getAssigneeUserId().equals(operatorUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.REPLENISHMENT_TASK_ASSIGNEE);
        }
        if (task.getCheckInAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CHECK_IN_REQUIRED);
        }
        ensureNoBlockingSession(deviceId);
        var otherTasks = replenishmentTaskRepository.findByDeviceIdAndStatusIn(deviceId, List.of("IN_PROGRESS"));
        if (otherTasks.stream().anyMatch(t -> !t.getTaskId().equals(taskId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_BUSY);
        }
        return task;
    }

    /** 销售结算：补货进行中（任务或补货会话）时阻断扣款。 */
    public void ensureSettlementAllowed(String deviceId) {
        if (isReplenishmentFrozen(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_IN_PROGRESS);
        }
    }

    public boolean isReplenishmentFrozen(String deviceId) {
        return hasActiveRestockSession(deviceId) || hasInProgressReplenishmentTask(deviceId);
    }

    private void ensureNoBlockingSession(String deviceId) {
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, BLOCKING_SESSION_STATES);
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_BUSY);
        }
    }

    private boolean hasActiveRestockSession(String deviceId) {
        return sessionRepository.findByDeviceIdAndStateIn(deviceId, BLOCKING_SESSION_STATES).stream()
                .anyMatch(DeviceValidationService::isRestockSession);
    }

    private boolean hasInProgressReplenishmentTask(String deviceId) {
        return !replenishmentTaskRepository.findByDeviceIdAndStatusIn(deviceId, List.of("IN_PROGRESS")).isEmpty();
    }

    static boolean isRestockSession(ShoppingSession session) {
        if (session.getReplenishmentTaskId() != null) {
            return true;
        }
        String key = session.getIdempotencyKey();
        return key != null && key.startsWith("RESTOCK:");
    }
}
