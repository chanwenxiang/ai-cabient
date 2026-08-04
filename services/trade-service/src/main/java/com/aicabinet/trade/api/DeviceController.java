package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceFaultReportRequest;
import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.DeviceCatalogService;
import com.aicabinet.trade.service.DeviceFaultReportService;
import com.aicabinet.trade.service.DeviceValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/devices")
public class DeviceController {

    private final DeviceValidationService deviceValidationService;
    private final DeviceCatalogService deviceCatalogService;
    private final DeviceFaultReportService deviceFaultReportService;

    public DeviceController(DeviceValidationService deviceValidationService,
                            DeviceCatalogService deviceCatalogService,
                            DeviceFaultReportService deviceFaultReportService) {
        this.deviceValidationService = deviceValidationService;
        this.deviceCatalogService = deviceCatalogService;
        this.deviceFaultReportService = deviceFaultReportService;
    }

    @GetMapping("/{deviceId}/status")
    public ApiResponse<DeviceStatusDto> status(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceValidationService.getDeviceStatus(deviceId));
    }

    @GetMapping("/{deviceId}/products")
    public ApiResponse<List<DeviceProductDto>> products(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceCatalogService.listProducts(deviceId));
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
