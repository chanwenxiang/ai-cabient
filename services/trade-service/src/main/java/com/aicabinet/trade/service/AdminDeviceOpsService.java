package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DeviceOpsCommandRequest;
import com.aicabinet.common.dto.DeviceOpsCommandResultDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AdminDeviceOpsService {

    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final DeviceValidationService deviceValidationService;
    private final DeviceSalesLockService salesLockService;
    private final DeviceServiceClient deviceClient;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;

    public AdminDeviceOpsService(DeviceInfoMapper deviceRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 DeviceValidationService deviceValidationService,
                                 DeviceSalesLockService salesLockService,
                                 DeviceServiceClient deviceClient,
                                 MerchantScopeService merchantScopeService,
                                 PermissionService permissionService,
                                 AdminAuditService auditService,
                                 DistributedLockService distributedLockService) {
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.deviceValidationService = deviceValidationService;
        this.salesLockService = salesLockService;
        this.deviceClient = deviceClient;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public DeviceOpsCommandResultDto execute(Long operatorId, String deviceId, DeviceOpsCommandRequest request) {
        permissionService.requireAnyPermission(operatorId, "ops:device:list", "ops:device:edit");
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo device = deviceRepository.findById(deviceId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));

        String cmd = request.command() == null ? "" : request.command().trim().toUpperCase();
        String reason = request.reason() == null || request.reason().isBlank()
                ? "admin-ops" : request.reason().trim();

        return switch (cmd) {
            case "OPEN_DOOR" -> remoteOpen(operatorId, device, reason);
            case "LOCK" -> lock(operatorId, device, reason, true);
            case "UNLOCK" -> lock(operatorId, device, reason, false);
            case "REBOOT" -> reboot(operatorId, device, reason);
            case "SET_TEMP" -> setTemp(operatorId, device, reason, request.targetTempC());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指令: " + cmd);
        };
    }

    /**
     * 运维远程开门：落真实 ShoppingSession（OPS_REMOTE），占柜、接门事件、可留录像；
     * 不结算、不出消费订单。与补货开门（RESTOCK）分离。
     */
    private DeviceOpsCommandResultDto remoteOpen(Long operatorId, DeviceInfo device, String reason) {
        return runWithDeviceOpenLock(device.getDeviceId(), () -> doRemoteOpen(operatorId, device, reason));
    }

    private DeviceOpsCommandResultDto doRemoteOpen(Long operatorId, DeviceInfo device, String reason) {
        deviceValidationService.ensureOpsRemoteDoorAllowed(device.getDeviceId());

        String sessionId = "ADM" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setUserId(operatorId);
        session.setDeviceId(device.getDeviceId());
        session.setIdempotencyKey("OPS_REMOTE:" + operatorId + ":" + sessionId);
        session.setState(SessionState.OPENING);
        sessionRepository.save(session);

        try {
            deviceClient.requestOpenDoorOperator(sessionId, device.getDeviceId(), operatorId);
        } catch (Exception e) {
            session.setState(SessionState.FAILED);
            session.setFailReason("开门指令下发失败");
            sessionRepository.save(session);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开门指令下发失败（请确认 device-service 在线）");
        }
        auditService.appendLog(operatorId, "DEVICE_REMOTE_OPEN", "SESSION", sessionId,
                "设备：" + device.getDeviceId() + "；" + reason);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "OPEN_DOOR", sessionId,
                "运维开门会话已创建并下发：" + sessionId, device.salesLockedEnabled());
    }

    private <T> T runWithDeviceOpenLock(String deviceId, java.util.function.Supplier<T> action) {
        String lockKey = SessionService.sessionOpenLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备开门处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private DeviceOpsCommandResultDto lock(Long operatorId, DeviceInfo device, String reason, boolean locked) {
        String commandId = salesLockService.applySalesLock(operatorId, device, locked, reason, true);
        // applySalesLock 内部 reload 写库，勿用调用方持有的旧实体判断结果
        return new DeviceOpsCommandResultDto(device.getDeviceId(), locked ? "LOCK" : "UNLOCK", commandId,
                locked ? "已锁机，消费者无法开门" : "已解锁，恢复营业", locked);
    }

    private DeviceOpsCommandResultDto reboot(Long operatorId, DeviceInfo device, String reason) {
        String commandId;
        try {
            commandId = deviceClient.requestOpsCommand(device.getDeviceId(), CabinetConstants.MQTT_CMD_REBOOT);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "重启指令下发失败（请确认 device-service 在线）");
        }
        auditService.appendLog(operatorId, "DEVICE_REBOOT", "DEVICE", device.getDeviceId(),
                reason + "；指令编号=" + commandId);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "REBOOT", commandId,
                "重启指令已下发", device.salesLockedEnabled());
    }

    private DeviceOpsCommandResultDto setTemp(Long operatorId, DeviceInfo device, String reason,
                                              Integer targetTempC) {
        if (targetTempC == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写目标温度");
        }
        if (targetTempC < -30 || targetTempC > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标温度应在 -30°C ~ 30°C 之间");
        }
        device.setTargetTempC(targetTempC);
        deviceRepository.save(device);
        String commandId = "LOCAL-" + UUID.randomUUID().toString().substring(0, 8);
        String message;
        if ("ONLINE".equalsIgnoreCase(device.getOnlineStatus())) {
            try {
                commandId = deviceClient.requestSetTargetTemp(device.getDeviceId(), targetTempC);
                message = "已向柜机下发目标温度 " + targetTempC + "°C";
            } catch (Exception e) {
                message = "设置已保存，柜机指令下发失败（请确认 device-service 在线）";
            }
        } else {
            message = "设置已保存，柜机离线时请上线后重新下发";
        }
        auditService.appendLog(operatorId, "DEVICE_SET_TEMP", "DEVICE", device.getDeviceId(),
                reason + "；目标温度=" + targetTempC + "℃；指令编号=" + commandId);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "SET_TEMP", commandId,
                message, device.salesLockedEnabled());
    }
}
