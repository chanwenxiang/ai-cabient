package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AdPlayEventRequest;
import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.OtaUpgradeProgressDto;
import com.aicabinet.common.dto.SkuQuantityDto;
import com.aicabinet.common.dto.ScreenContentDto;
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

    public DeviceInternalController(DevicePresenceService presenceService,
                                    OtaService otaService,
                                    DeviceSlotService deviceSlotService,
                                    DeviceEnvService envService,
                                    AdCampaignService adCampaignService) {
        this.presenceService = presenceService;
        this.otaService = otaService;
        this.deviceSlotService = deviceSlotService;
        this.envService = envService;
        this.adCampaignService = adCampaignService;
    }

    /** 供 device-service 发令前校验柜机是否已注册（B-22）。 */
    @GetMapping("/{deviceId}/exists")
    public ApiResponse<Map<String, Object>> exists(@PathVariable("deviceId") String deviceId) {
        boolean exists = presenceService.deviceExists(deviceId);
        return ApiResponse.ok(Map.of("deviceId", deviceId == null ? "" : deviceId, "exists", exists));
    }

    /** 消费者小程序推广位：拉取当前投放内容（自有素材/多媒体轮播）。 */
    @GetMapping("/{deviceId}/screen-content")
    public ApiResponse<ScreenContentDto> screenContent(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(adCampaignService.screenContent(deviceId));
    }

    /**
     * 小程序推广位曝光/完播/点击回写（ROI 留痕）。
     *
     * <p>🔴 服务端按「设备 × 计划 × 素材 × 事件类型」在 60s 窗口内去重
     * （{@code AdPlayEventDeduplicator}）—— 客户端去重（{@code impressed}/{@code completeTimers}）
     * 可被改包或脚本绕过，服务端才是最后一道。
     */
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

    /**
     * 设备侧上报 OTA 升级进度（O2）。
     *
     * <p>开关 {@code ota.progress.enabled} 关闭时服务端不落库，响应体为 {@code null} ——
     * 设备侧只需看 HTTP 200 即可，不必解读内容（老固件不发这个请求，新固件发了也不会因开关而失败）。
     */
    @PostMapping("/{deviceId}/ota/progress")
    public ApiResponse<OtaUpgradeProgressDto> reportOtaProgress(
            @PathVariable("deviceId") String deviceId,
            @RequestBody OtaProgressRequest body) {
        return ApiResponse.ok(otaService.reportProgress(
                deviceId,
                body.targetVersion(),
                body.status(),
                body.progressPercent(),
                body.errorMessage()).orElse(null));
    }

    /** 供 vision-service 补货库存快照模式拉取柜内 SKU 汇总数量。 */
    @GetMapping("/{deviceId}/inventory-snapshot")
    public ApiResponse<List<SkuQuantityDto>> inventorySnapshot(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(deviceSlotService.inventorySnapshot(deviceId));
    }

    record HeartbeatRequest(String appVersion, String firmwareVersion, Integer currentTempC,
                            Double humidityPct, Double voltageV, Double powerW,
                            String imei, String boardSn) {}

    /** OTA 进度上报请求体；字段全可空，缺省即 IDLE / 0%。 */
    record OtaProgressRequest(String targetVersion, String status, Integer progressPercent,
                              String errorMessage) {}
}
