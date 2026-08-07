package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DeviceAvailabilityKpiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备可用性 KPI（日快照，由 deviceAvailabilityKpiDailyJob 生成）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/device-availability-kpi")
public class DeviceAvailabilityKpiController {

    private final DeviceAvailabilityKpiService kpiService;

    public DeviceAvailabilityKpiController(DeviceAvailabilityKpiService kpiService) {
        this.kpiService = kpiService;
    }

    @RequiresPermissions("ops:analytics:view")
    @GetMapping
    public ApiResponse<List<DeviceAvailabilityKpiDto>> list(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(kpiService.recentDays(days));
    }
}
