package com.aicabinet.trade.api;

import com.aicabinet.common.dto.AdPlayEventRequest;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceFaultReportRequest;
import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.common.dto.NearbyDeviceDto;
import com.aicabinet.common.dto.ScreenContentDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.DeviceCatalogService;
import com.aicabinet.trade.service.DeviceFaultReportService;
import com.aicabinet.trade.service.DeviceValidationService;
import com.aicabinet.trade.service.NearbyDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/devices")
public class DeviceController {

    private final DeviceValidationService deviceValidationService;
    private final DeviceCatalogService deviceCatalogService;
    private final DeviceFaultReportService deviceFaultReportService;
    private final NearbyDeviceService nearbyDeviceService;
    private final AdCampaignService adCampaignService;

    public DeviceController(DeviceValidationService deviceValidationService,
                            DeviceCatalogService deviceCatalogService,
                            DeviceFaultReportService deviceFaultReportService,
                            NearbyDeviceService nearbyDeviceService,
                            AdCampaignService adCampaignService) {
        this.deviceValidationService = deviceValidationService;
        this.deviceCatalogService = deviceCatalogService;
        this.deviceFaultReportService = deviceFaultReportService;
        this.nearbyDeviceService = nearbyDeviceService;
        this.adCampaignService = adCampaignService;
    }

    /** 附近柜机：按经纬度距离排序，含库存预览（需登录）。 */
    @GetMapping("/nearby")
    public ApiResponse<List<NearbyDeviceDto>> nearby(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "radiusKm", defaultValue = "5") double radiusKm,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(nearbyDeviceService.listNearby(lat, lng, radiusKm, limit));
    }

    @GetMapping("/{deviceId}/status")
    public ApiResponse<DeviceStatusDto> status(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceValidationService.getDeviceStatus(deviceId));
    }

    @GetMapping("/{deviceId}/products")
    public ApiResponse<List<DeviceProductDto>> products(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceCatalogService.listProducts(deviceId));
    }

    /** 开门页/柜机屏：拉取当前生效的投放轮播（需登录）。 */
    @GetMapping("/{deviceId}/screen-content")
    public ApiResponse<ScreenContentDto> screenContent(@PathVariable String deviceId) {
        return ApiResponse.ok(adCampaignService.screenContent(deviceId));
    }

    /** 开门页曝光/完播回写，供投放 ROI。 */
    @PostMapping("/{deviceId}/ad-play")
    public ApiResponse<Void> adPlay(
            @PathVariable String deviceId,
            @Valid @RequestBody AdPlayEventRequest body) {
        adCampaignService.recordPlayEvent(deviceId, body.campaignId(), body.assetId(), body.eventType());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{deviceId}/fault-report")
    public ApiResponse<Map<String, String>> faultReport(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceFaultReportRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(deviceFaultReportService.report(userId, deviceId, body));
    }
}
