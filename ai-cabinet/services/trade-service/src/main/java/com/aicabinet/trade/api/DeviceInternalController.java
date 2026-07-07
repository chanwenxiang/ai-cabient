package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.trade.service.DevicePresenceService;
import com.aicabinet.trade.service.OtaService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

    private final DevicePresenceService presenceService;
    private final OtaService otaService;

    public DeviceInternalController(DevicePresenceService presenceService, OtaService otaService) {
        this.presenceService = presenceService;
        this.otaService = otaService;
    }

    @PostMapping("/{deviceId}/heartbeat")
    public ApiResponse<Void> heartbeat(
            @PathVariable("deviceId") String deviceId,
            @RequestBody(required = false) HeartbeatRequest body) {
        String appVersion = body != null ? body.appVersion() : null;
        String firmwareVersion = body != null ? body.firmwareVersion() : null;
        presenceService.heartbeat(deviceId, appVersion, firmwareVersion);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{deviceId}/ota/check")
    public ApiResponse<OtaCheckResponse> checkOta(
            @PathVariable("deviceId") String deviceId,
            @RequestParam String currentVersion,
            @RequestParam(defaultValue = "stable") String channel) {
        otaService.reportVersion(deviceId, currentVersion);
        return ApiResponse.ok(otaService.checkUpdate(deviceId, currentVersion, channel));
    }

    record HeartbeatRequest(String appVersion, String firmwareVersion) {}
}
