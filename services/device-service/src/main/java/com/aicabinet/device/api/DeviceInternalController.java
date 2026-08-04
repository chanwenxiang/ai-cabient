package com.aicabinet.device.api;

import com.aicabinet.device.service.DeviceCommandService;
import com.aicabinet.device.service.DeviceCommandTracker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

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
        String type = request.command() == null ? "" : request.command().trim().toUpperCase();
        if (!type.equals("LOCK") && !type.equals("UNLOCK") && !type.equals("REBOOT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported command");
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
