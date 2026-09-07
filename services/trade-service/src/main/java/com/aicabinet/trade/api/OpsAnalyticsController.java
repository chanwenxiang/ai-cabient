package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FootfallAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客流 / 热区分析（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsAnalyticsController {

    private final FootfallAnalyticsService footfallAnalyticsService;

    public OpsAnalyticsController(FootfallAnalyticsService footfallAnalyticsService) {
        this.footfallAnalyticsService = footfallAnalyticsService;
    }

    // --- 客流 / 时段热区 / 坪效分析 ---
    @RequiresPermissions("ops:analytics:footfall:view")
    @GetMapping("/analytics/footfall")
    public ApiResponse<FootfallAnalyticsDto> footfallAnalytics(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "deviceLimit", defaultValue = "50") int deviceLimit,
            @RequestParam(name = "skuLimit", defaultValue = "20") int skuLimit) {
        return ApiResponse.ok(footfallAnalyticsService.analytics(days, deviceLimit, skuLimit));
    }

    @RequiresPermissions("ops:analytics:footfall:view")
    @GetMapping("/analytics/footfall/slots")
    public ApiResponse<List<SlotHeatDto>> footfallSlotHeat(
            HttpServletRequest request,
            @RequestParam String deviceId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(footfallAnalyticsService.slotHeat(deviceId, days));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
