package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.SkuQuantityDto;
import com.aicabinet.trade.service.DevicePresenceService;
import com.aicabinet.trade.service.DeviceSlotService;
import com.aicabinet.trade.service.OtaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

    private final DevicePresenceService presenceService;
    private final OtaService otaService;
    private final DeviceSlotService deviceSlotService;

    public DeviceInternalController(DevicePresenceService presenceService,
                                    OtaService otaService,
                                    DeviceSlotService deviceSlotService) {
        this.presenceService = presenceService;
        this.otaService = otaService;
        this.deviceSlotService = deviceSlotService;
    }

    @PostMapping("/{deviceId}/heartbeat")
    public ApiResponse<Void> heartbeat(
            @PathVariable("deviceId") String deviceId,
            @RequestBody(required = false) HeartbeatRequest body) {
        String appVersion = body != null ? body.appVersion() : null;
        String firmwareVersion = body != null ? body.firmwareVersion() : null;
        Integer currentTempC = body != null ? body.currentTempC() : null;
        presenceService.heartbeat(deviceId, appVersion, firmwareVersion, currentTempC);
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

    /** 供 vision-service 补货库存快照模式拉取柜内 SKU 汇总数量。 */
    @GetMapping("/{deviceId}/inventory-snapshot")
    public ApiResponse<List<SkuQuantityDto>> inventorySnapshot(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(deviceSlotService.inventorySnapshot(deviceId));
    }

    record HeartbeatRequest(String appVersion, String firmwareVersion, Integer currentTempC) {}
}
