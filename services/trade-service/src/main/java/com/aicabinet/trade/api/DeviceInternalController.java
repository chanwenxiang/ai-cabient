package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AdPlayEventRequest;
import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.SkuQuantityDto;
import com.aicabinet.common.dto.ScreenContentDto;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.service.DevicePresenceService;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.DeviceSlotService;
import com.aicabinet.trade.service.OtaService;
import com.aicabinet.trade.service.AdCampaignService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/devices")
public class DeviceInternalController {

    private final DevicePresenceService presenceService;
    private final OtaService otaService;
    private final DeviceSlotService deviceSlotService;
    private final DeviceEnvService envService;
    private final AdCampaignService adCampaignService;
    private final DeviceInfoMapper deviceRepository;

    public DeviceInternalController(DevicePresenceService presenceService,
                                    OtaService otaService,
                                    DeviceSlotService deviceSlotService,
                                    DeviceEnvService envService,
                                    AdCampaignService adCampaignService,
                                    DeviceInfoMapper deviceRepository) {
        this.presenceService = presenceService;
        this.otaService = otaService;
        this.deviceSlotService = deviceSlotService;
        this.envService = envService;
        this.adCampaignService = adCampaignService;
        this.deviceRepository = deviceRepository;
    }

    /** 供 device-service 发令前校验柜机是否已注册（B-22）。 */
    @GetMapping("/{deviceId}/exists")
    public ApiResponse<Map<String, Object>> exists(@PathVariable("deviceId") String deviceId) {
        boolean exists = deviceId != null
                && !deviceId.isBlank()
                && deviceRepository.selectById(deviceId) != null;
        return ApiResponse.ok(Map.of("deviceId", deviceId == null ? "" : deviceId, "exists", exists));
    }

    /** 设备屏拉取当前投放内容（广告/多媒体轮播）。 */
    @GetMapping("/{deviceId}/screen-content")
    public ApiResponse<ScreenContentDto> screenContent(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(adCampaignService.screenContent(deviceId));
    }

    /** 柜屏曝光/完播回写（ROI 留痕）。 */
    @PostMapping("/{deviceId}/ad-play")
    public ApiResponse<Void> adPlay(
            @PathVariable("deviceId") String deviceId,
            @RequestBody AdPlayEventRequest body) {
        adCampaignService.recordPlayEvent(deviceId, body.campaignId(), body.assetId(), body.eventType());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{deviceId}/heartbeat")
    public ApiResponse<Void> heartbeat(
            @PathVariable("deviceId") String deviceId,
            @RequestBody(required = false) HeartbeatRequest body) {
        String appVersion = body != null ? body.appVersion() : null;
        String firmwareVersion = body != null ? body.firmwareVersion() : null;
        Integer currentTempC = body != null ? body.currentTempC() : null;
        String imei = body != null ? body.imei() : null;
        String boardSn = body != null ? body.boardSn() : null;
        presenceService.heartbeat(deviceId, appVersion, firmwareVersion, currentTempC, imei, boardSn);
        if (body != null) {
            envService.saveReading(deviceId, body.humidityPct(), body.voltageV(), body.powerW());
        }
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

    record HeartbeatRequest(String appVersion, String firmwareVersion, Integer currentTempC,
                            Double humidityPct, Double voltageV, Double powerW,
                            String imei, String boardSn) {}
}
