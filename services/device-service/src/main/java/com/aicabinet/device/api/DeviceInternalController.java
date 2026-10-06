package com.aicabinet.device.api;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.device.service.DeviceCommandService;
import com.aicabinet.device.service.DeviceCommandTracker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

    /**
     * V308：允许下发的运维指令全集。
     *
     * <p>🔴 <b>常量单一来源</b>：值取自 {@link CabinetConstants} 的 {@code MQTT_CMD_*}，
     * 而 device-service 并不依赖 Android 端代码 —— 两边必须用<b>同一批字面量</b>，
     * 故这里集中声明并在 {@code opsCommand} 里做<b>集合</b>判定（不是 if-else 链）。
     * 若 edge 端新增了指令而这里没加，症状是「设备静默丢弃、云端 15s 超时」——
     * 集合形式让漏改更容易被 review 发现。
     */
    private static final Set<String> OPS_COMMANDS = Set.of(
            CabinetConstants.MQTT_CMD_LOCK,
            CabinetConstants.MQTT_CMD_UNLOCK,
            CabinetConstants.MQTT_CMD_REBOOT,
            CabinetConstants.MQTT_CMD_SELF_TEST
    );

    private final DeviceCommandService commandService;

    public DeviceInternalController(DeviceCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping("/{deviceId}/open-door")
    public OpenDoorResponse openDoor(
            @PathVariable("deviceId") String deviceId,
            @RequestBody OpenDoorRequest request) {
        String commandId = commandService.openDoor(deviceId, request.sessionId(), request.userId(), request.operatorMode());
        return new OpenDoorResponse(commandId);
    }

    @GetMapping("/commands/{commandId}")
    public DeviceCommandTracker.CommandStatus commandStatus(@PathVariable("commandId") String commandId) {
        DeviceCommandTracker.CommandStatus status = commandService.getCommandStatus(commandId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found");
        }
        return status;
    }

    @PostMapping("/{deviceId}/set-target-temp")
    public SetTargetTempResponse setTargetTemp(
            @PathVariable("deviceId") String deviceId,
            @RequestBody SetTargetTempRequest request) {
        String commandId = commandService.setTargetTemp(deviceId, request.targetTempC());
        return new SetTargetTempResponse(commandId);
    }

    @PostMapping("/{deviceId}/ops-command")
    public OpsCommandResponse opsCommand(
            @PathVariable("deviceId") String deviceId,
            @RequestBody OpsCommandRequest request) {
        String type = request.command() == null ? "" : request.command().trim().toUpperCase(Locale.ROOT);
        // V308：补 SELF_TEST（远程触发自检）。用 Set 而非 if-else 链 ——
        // 新增指令时漏一处就是「云端能下发、设备静默丢弃」，是最难排查的一类漂移。
        if (!OPS_COMMANDS.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported command: " + type + "（支持 " + OPS_COMMANDS.stream()
                            .sorted().collect(Collectors.joining("/")) + "）");
        }
        String commandId = commandService.sendOpsCommand(deviceId, type);
        return new OpsCommandResponse(commandId, type);
    }

    record OpenDoorRequest(String sessionId, Long userId, boolean operatorMode) {}
    record OpenDoorResponse(String commandId) {}
    record SetTargetTempRequest(int targetTempC) {}
    record SetTargetTempResponse(String commandId) {}
    record OpsCommandRequest(String command) {}
    record OpsCommandResponse(String commandId, String command) {}
}
