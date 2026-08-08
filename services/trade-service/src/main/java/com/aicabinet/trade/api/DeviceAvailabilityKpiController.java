package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DeviceAvailabilityKpiService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    @RequiresPermissions("ops:device-kpi:view")
    @GetMapping
    public ApiResponse<DeviceAvailabilityKpiDto> get(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(date == null ? kpiService.today() : kpiService.getByDate(date));
    }
}
