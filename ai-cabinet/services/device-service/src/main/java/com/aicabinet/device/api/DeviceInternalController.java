package com.aicabinet.device.api;

import com.aicabinet.device.service.DeviceCommandService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

    private final DeviceCommandService commandService;

    public DeviceInternalController(DeviceCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping("/{deviceId}/open-door")
    public void openDoor(
            @PathVariable("deviceId") String deviceId,
            @RequestBody OpenDoorRequest request) {
        commandService.openDoor(deviceId, request.sessionId(), request.userId(), request.operatorMode());
    }

    record OpenDoorRequest(String sessionId, Long userId, boolean operatorMode) {}
}
