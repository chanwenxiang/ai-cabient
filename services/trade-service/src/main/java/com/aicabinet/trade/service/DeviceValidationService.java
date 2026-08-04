package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DeviceNameSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class DeviceValidationService {

    /** 含 WAITING_UPLOAD：关门后识别/结算未完成前禁止新开门，避免账实错位。 */
    private static final List<SessionState> BLOCKING_SESSION_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.WAITING_UPLOAD, SessionState.RECOGNIZING, SessionState.SETTLING
    );

    private static final List<SessionState> ACTIVE_SESSION_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING, SessionState.RECOGNIZING,
            SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private final DeviceInfoMapper deviceInfoRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final ConsumerPreauthService consumerPreauthService;

    public DeviceValidationService(DeviceInfoMapper deviceInfoRepository,
                                   ShoppingSessionMapper sessionRepository,
                                   ReplenishmentTaskMapper replenishmentTaskRepository,
                                   ConsumerPreauthService consumerPreauthService) {
        this.deviceInfoRepository = deviceInfoRepository;
        this.sessionRepository = sessionRepository;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.consumerPreauthService = consumerPreauthService;
    }

    public DeviceInfo requireDevice(String deviceId) {
        return deviceInfoRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
    }

    public DeviceStatusDto getDeviceStatus(String deviceId) {
        DeviceInfo device = requireDevice(deviceId);
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_SESSION_STATES);
        String activeSessionId = active.isEmpty() ? null : active.get(0).getSessionId();
        String activeSessionState = active.isEmpty() ? null : active.get(0).getState().name();
        boolean replenishment = hasInProgressReplenishmentTask(deviceId);
        boolean locked = device.salesLockedEnabled();
        boolean available = active.isEmpty() && !replenishment && !locked;
        String busyReason = "NONE";
        if (!available) {
            if (locked) {
                busyReason = "LOCKED";
            } else {
                busyReason = !active.isEmpty() ? "SESSION" : "REPLENISHMENT";
            }
        }
        boolean online = "ONLINE".equalsIgnoreCase(device.getOnlineStatus());
        return new DeviceStatusDto(
                device.getDeviceId(),
                DeviceNameSupport.resolve(device.getDeviceId(), device.getDeviceName()),
                device.getOnlineStatus(),
                online,
                available,
                activeSessionId,
                activeSessionState,
                busyReason,
                consumerPreauthService.resolvePreauthCents(deviceId)
        );
    }

    /** 消费者开门：无占用会话，且无进行中的补货任务。 */
    public void ensureDeviceAvailable(String deviceId) {
        ensureConsumerShoppingAllowed(deviceId);
    }

    public void ensureConsumerShoppingAllowed(String deviceId) {
        DeviceInfo device = ensureDeviceOnline(deviceId);
        if (device.salesLockedEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已暂停营业，请稍后再试");
        }
        ensureNoBlockingSession(deviceId);
        if (hasInProgressReplenishmentTask(deviceId)) {
            // 与结算冻结文案一致，避免消费者误以为柜机故障
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_IN_PROGRESS);
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
        // 运营账号可代开；商户/补货员须为任务负责人（或未指定负责人）
        if (task.getAssigneeUserId() != null
                && !task.getAssigneeUserId().equals(operatorUserId)
                && !OperatorAuth.isOperator(operatorUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.REPLENISHMENT_TASK_ASSIGNEE);
        }
        if (task.getCheckInAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CHECK_IN_REQUIRED);
        }
        ensureDeviceOnline(deviceId);
        // 仅活跃会话占柜；同柜多条「已签到/进行中」任务不互斥（避免签到后互相卡死开门）
        ensureNoBlockingSession(deviceId);
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

    /**
     * 运维远程开门：须在线、无占用会话。
     * 允许在锁机停售下开门（检修场景）；消费者开门仍受 salesLocked 约束。
     */
    public DeviceInfo ensureOpsRemoteDoorAllowed(String deviceId) {
        DeviceInfo device = ensureDeviceOnline(deviceId);
        ensureNoBlockingSession(deviceId);
        return device;
    }

    private void ensureNoBlockingSession(String deviceId) {
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, BLOCKING_SESSION_STATES);
        if (!active.isEmpty()) {
            boolean restock = active.stream().anyMatch(DeviceValidationService::isRestockSession);
            boolean ops = active.stream().anyMatch(DeviceValidationService::isOpsRemoteSession);
            String msg = restock ? ApiMessages.RESTOCK_DOOR_SESSION_BUSY
                    : (ops ? "设备运维开门会话进行中，请先关门或结束后再试" : ApiMessages.DEVICE_BUSY);
            throw new ResponseStatusException(HttpStatus.CONFLICT, msg);
        }
    }

    private DeviceInfo ensureDeviceOnline(String deviceId) {
        DeviceInfo device = requireDevice(deviceId);
        if (!"ONLINE".equalsIgnoreCase(device.getOnlineStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_OFFLINE);
        }
        return device;
    }

    private boolean hasActiveRestockSession(String deviceId) {
        return sessionRepository.findByDeviceIdAndStateIn(deviceId, BLOCKING_SESSION_STATES).stream()
                .anyMatch(DeviceValidationService::isRestockSession);
    }

    /**
     * 仅「已签到」的进行中补货任务冻结购物/结算。
     * 出库明细生成会把任务标成 IN_PROGRESS，但商户尚未到店时不应挡消费者。
     */
    private boolean hasInProgressReplenishmentTask(String deviceId) {
        return replenishmentTaskRepository.findByDeviceIdAndStatusIn(deviceId, List.of("IN_PROGRESS")).stream()
                .anyMatch(t -> t.getCheckInAt() != null);
    }

    static boolean isRestockSession(ShoppingSession session) {
        if (session.getReplenishmentTaskId() != null) {
            return true;
        }
        String key = session.getIdempotencyKey();
        return key != null && key.startsWith("RESTOCK:");
    }

    /** 运维远程开门会话：不结算、不出单，仅占柜与留痕。 */
    static boolean isOpsRemoteSession(ShoppingSession session) {
        String key = session.getIdempotencyKey();
        return key != null && key.startsWith("OPS_REMOTE:");
    }

    /** 非消费者购物会话（补货 / 运维），关门后不走扣款结算。 */
    static boolean isNonConsumerSession(ShoppingSession session) {
        return isRestockSession(session) || isOpsRemoteSession(session);
    }

    static String sessionKind(ShoppingSession session) {
        if (isOpsRemoteSession(session)) {
            return "OPS";
        }
        if (isRestockSession(session)) {
            return "RESTOCK";
        }
        return "CONSUMER";
    }
}
