package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.DeviceTempPlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备温控计划与环境监测（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsDeviceEnvController {

    private final DeviceTempPlanService deviceTempPlanService;
    private final DeviceEnvService deviceEnvService;
    private final AdminAuditService auditService;

    public OpsDeviceEnvController(DeviceTempPlanService deviceTempPlanService,
                                  DeviceEnvService deviceEnvService,
                                  AdminAuditService auditService) {
        this.deviceTempPlanService = deviceTempPlanService;
        this.deviceEnvService = deviceEnvService;
        this.auditService = auditService;
    }

    // --- 设备：温控计划 + 环境多指标监控 ---
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/temp-plan")
    public ApiResponse<DeviceTempPlanDto> tempPlan(
            HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(deviceTempPlanService.get(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/temp-plan")
    public ApiResponse<DeviceTempPlanDto> upsertTempPlan(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody UpsertDeviceTempPlanRequest body) {
        return ApiResponse.ok(deviceTempPlanService.upsert(
                operatorId(request), deviceId, body.enabled(), body.entries()));
    }

    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/temp-plan/apply")
    public ApiResponse<DeviceTempPlanDto> applyTempPlan(
            HttpServletRequest request, @PathVariable String deviceId) {
        DeviceTempPlanDto dto = deviceTempPlanService.applyNow(operatorId(request), deviceId);
        auditService.appendLog(operatorId(request), "DEVICE_TEMP_PLAN_APPLY", "DEVICE_TEMP_PLAN",
                deviceId, "enabled=" + (dto == null ? null : dto.enabled())
                        + " entries=" + (dto == null ? null : dto.entries().size()));
        return ApiResponse.ok(dto);
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/env-readings")
    public ApiResponse<List<DeviceEnvReadingDto>> envReadings(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return ApiResponse.ok(deviceEnvService.list(deviceId, type, hours, limit));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
