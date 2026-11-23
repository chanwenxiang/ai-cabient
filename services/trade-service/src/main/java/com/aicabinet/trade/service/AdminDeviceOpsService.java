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
import org.springframework.context.annotation.Lazy;
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
    private final AdminDeviceOpsService self;
    private final SessionService sessionService;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final JiangyiDeviceOpsService jiangyiDeviceOpsService;

    public AdminDeviceOpsService(DeviceInfoMapper deviceRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 DeviceValidationService deviceValidationService,
                                 DeviceSalesLockService salesLockService,
                                 DeviceServiceClient deviceClient,
                                 MerchantScopeService merchantScopeService,
                                 PermissionService permissionService,
                                 AdminAuditService auditService,
                                 DistributedLockService distributedLockService,
                                 @Lazy AdminDeviceOpsService self,
                                 @Lazy SessionService sessionService,
                                 JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                 JiangyiDeviceOpsService jiangyiDeviceOpsService) {
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.deviceValidationService = deviceValidationService;
        this.salesLockService = salesLockService;
        this.deviceClient = deviceClient;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
        this.sessionService = sessionService;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.jiangyiDeviceOpsService = jiangyiDeviceOpsService;
    }

    /** 无外层长事务：落库短事务与 device-service MQTT 指令分离。 */
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
            // V308 端侧自检早已实现，此前缺云端触发入口（图2 对照「货柜检测」，2026-10-15 补）
            case "SELF_TEST" -> selfTest(operatorId, device, reason);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指令: " + cmd);
        };
    }

    /**
     * 商户端设备控制（CB-050，2026-10-16 权限化下放）：白名单 LOCK/UNLOCK/REBOOT。
     * OPEN_DOOR 不开放（运维免结算开门=资金敏感通道，补货走补货任务链路）；SET_TEMP 属平台运营参数。
     * 审计与 admin 同路（appendLog operatorId=商户侧用户 ID）。
     */
    public DeviceOpsCommandResultDto executeForMerchant(Long userId, String deviceId, DeviceOpsCommandRequest request) {
        permissionService.requirePermission(userId, "merchant:device:control");
        merchantScopeService.requireDeviceAccess(userId, deviceId);
        DeviceInfo device = deviceRepository.findById(deviceId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        String cmd = request.command() == null ? "" : request.command().trim().toUpperCase();
        String reason = request.reason() == null || request.reason().isBlank()
                ? "merchant-portal" : request.reason().trim();
        return switch (cmd) {
            case "LOCK" -> lock(userId, device, reason, true);
            case "UNLOCK" -> lock(userId, device, reason, false);
            case "REBOOT" -> reboot(userId, device, reason);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "商户端仅支持 停售/恢复售卖/重启 指令；远程开门请走补货任务，温控请联系平台");
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
        self.createOpsRemoteOpeningSession(sessionId, operatorId, device.getDeviceId());

        try {
            deviceClient.requestOpenDoorOperator(sessionId, device.getDeviceId(), operatorId);
        } catch (ResponseStatusException e) {
            // 路由层的明确拒绝（如将邑柜 501 不支持运维开门）原样透出，不再改写成 device-service 文案
            self.markOpsRemoteSessionFailed(sessionId,
                    e.getReason() != null ? e.getReason() : "开门指令下发失败");
            throw e;
        } catch (Exception e) {
            self.markOpsRemoteSessionFailed(sessionId, "开门指令下发失败");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开门指令下发失败（请确认 device-service 在线）");
        }
        auditService.appendLog(operatorId, "DEVICE_REMOTE_OPEN", "SESSION", sessionId,
                "设备：" + device.getDeviceId() + "；" + reason);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "OPEN_DOOR", sessionId,
                "运维开门会话已创建并下发：" + sessionId, device.salesLockedEnabled());
    }

    @Transactional
    public void createOpsRemoteOpeningSession(String sessionId, Long operatorId, String deviceId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setUserId(operatorId);
        session.setDeviceId(deviceId);
        session.setIdempotencyKey("OPS_REMOTE:" + operatorId + ":" + sessionId);
        session.setState(SessionState.OPENING);
        sessionRepository.save(session);
    }

    @Transactional
    public void markOpsRemoteSessionFailed(String sessionId, String failReason) {
        ShoppingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (session.getState() == SessionState.FAILED
                || session.getState() == SessionState.CANCELLED
                || session.getState() == SessionState.COMPLETED) {
            return;
        }
        session.setFailReason(failReason);
        sessionService.transition(session, SessionState.FAILED);
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

    private DeviceOpsCommandResultDto selfTest(Long operatorId, DeviceInfo device, String reason) {
        if (jiangyiDeviceDirectory.isJiangyi(device.getDeviceId())) {
            // 将邑 V16 下行 17 条无自检消息（我方实现的 10 个 msgType 里也没有）。
            // 旧行为：MQTT 发一条没人收的 SELF_TEST ⇒ 回「已下发」+ 15s TIMEOUT = 假成功。
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "将邑柜协议无远程自检指令（可用设备卡的「查询门磁/拉取设备信息」代替）");
        }
        String commandId;
        try {
            commandId = deviceClient.requestOpsCommand(device.getDeviceId(), CabinetConstants.MQTT_CMD_SELF_TEST);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "自检指令下发失败（请确认 device-service 在线）");
        }
        auditService.appendLog(operatorId, "DEVICE_SELF_TEST", "DEVICE", device.getDeviceId(),
                reason + "；指令编号=" + commandId);
        return new DeviceOpsCommandResultDto(device.getDeviceId(), "SELF_TEST", commandId,
                "自检指令已下发，结果见设备自检记录", device.salesLockedEnabled());
    }

    private DeviceOpsCommandResultDto reboot(Long operatorId, DeviceInfo device, String reason) {
        if (jiangyiDeviceDirectory.isJiangyi(device.getDeviceId())) {
            // 将邑柜不接 MQTT，reboot 走网关 WS（§4.3.2.1，2026-11-10 真机验：约 28s 后重连）。
            // 🔴 必须复用 JiangyiDeviceOpsService 而不是直接发网关请求 —— 它带
            //    「进行中业务」闸（消费者会话 / 已签到补货 ⇒ 409，force 才越过）与审计留痕；
            //    自己再写一份 = 第二份闸，迟早漂移（同族判据见 check:ops-device-scope）。
            jiangyiDeviceOpsService.reboot(operatorId, device.getDeviceId(), false);
            return new DeviceOpsCommandResultDto(device.getDeviceId(), "REBOOT", null,
                    "已将邑柜重启指令经网关下发（协议无指令号；重启后 WebSocket 会短暂断开）",
                    device.salesLockedEnabled());
        }
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
        self.persistTargetTemp(device.getDeviceId(), targetTempC);
        String commandId = "LOCAL-" + UUID.randomUUID().toString().substring(0, 8);
        String message;
        if (jiangyiDeviceDirectory.isJiangyi(device.getDeviceId())) {
            // 将邑柜无远程控温（V16 下行无温控消息）。目标温度仍落库（运营记录/报表口径），
            // 但文案必须说清「柜机不会变温」，否则运营以为设过了。
            message = "目标温度已记录；将邑柜协议无远程控温指令，需现场在柜机上设置";
        } else if ("ONLINE".equalsIgnoreCase(device.getOnlineStatus())) {
            try {
                commandId = deviceClient.requestSetTargetTemp(device.getDeviceId(), targetTempC);
                message = "已向柜机下发目标温度 " + targetTempC + "°C";
            } catch (ResponseStatusException e) {
                // 路由层的明确拒绝原样透出（同 doRemoteOpen 的处理方式），别再套「device-service 在线」——
                // 那是把「通道不对」误报成「服务挂了」，会把排查引向错误方向。
                message = "设置已保存，但" + (e.getReason() == null ? "柜机不受理该指令" : e.getReason());
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

    @Transactional
    public void persistTargetTemp(String deviceId, int targetTempC) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        device.setTargetTempC(targetTempC);
        deviceRepository.save(device);
    }
}
