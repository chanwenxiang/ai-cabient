package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DeviceOpsCommandRequest;
import com.aicabinet.common.dto.DeviceOpsCommandResultDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AdminDeviceOpsService {

    private final DeviceInfoMapper deviceRepository;
    private final DeviceServiceClient deviceClient;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public AdminDeviceOpsService(DeviceInfoMapper deviceRepository,
                                 DeviceServiceClient deviceClient,
                                 MerchantScopeService merchantScopeService,
                                 PermissionService permissionService,
                                 AdminAuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.deviceClient = deviceClient;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
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

    private DeviceOpsCommandResultDto remoteOpen(Long operatorId, DeviceInfo device, String reason) {
        if (device.salesLockedEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已锁机，请先解锁再远程开门");
        }
        String sessionId = "ADM" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String commandId;
        try {
            deviceClient.requestOpenDoorOperator(sessionId, device.getDeviceId(), operatorId);
            commandId = sessionId;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开门指令下发失败（请确认 device-service 在线）");
        }
        auditService.record(operatorId, "DEVICE_REMOTE_OPEN", "DEVICE", device.getDeviceId(), reason);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "OPEN_DOOR", commandId,
                "远程开门指令已下发", device.salesLockedEnabled());
    }

    private DeviceOpsCommandResultDto lock(Long operatorId, DeviceInfo device, String reason, boolean locked) {
        String mqttCmd = locked ? CabinetConstants.MQTT_CMD_LOCK : CabinetConstants.MQTT_CMD_UNLOCK;
        String commandId;
        try {
            commandId = deviceClient.requestOpsCommand(device.getDeviceId(), mqttCmd);
        } catch (Exception e) {
            // 本地仍更新锁机状态，保证运营可强制停售；边端可能稍后同步
            commandId = "LOCAL-" + UUID.randomUUID().toString().substring(0, 8);
        }
        device.setSalesLocked(locked);
        deviceRepository.save(device);
        auditService.record(operatorId, locked ? "DEVICE_LOCK" : "DEVICE_UNLOCK",
                "DEVICE", device.getDeviceId(), reason + "; commandId=" + commandId);
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
        auditService.record(operatorId, "DEVICE_REBOOT", "DEVICE", device.getDeviceId(),
                reason + "; commandId=" + commandId);
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
        String message = "目标温度已保存为 " + targetTempC + "°C";
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
        auditService.record(operatorId, "DEVICE_SET_TEMP", "DEVICE", device.getDeviceId(),
                reason + "; targetTempC=" + targetTempC + "; commandId=" + commandId);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "SET_TEMP", commandId,
                message, device.salesLockedEnabled());
    }
}
