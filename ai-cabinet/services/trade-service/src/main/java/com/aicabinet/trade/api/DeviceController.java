package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.trade.service.DeviceValidationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/devices")
public class DeviceController {

    private final DeviceValidationService deviceValidationService;

    public DeviceController(DeviceValidationService deviceValidationService) {
        this.deviceValidationService = deviceValidationService;
    }

    @GetMapping("/{deviceId}/status")
    public ApiResponse<DeviceStatusDto> status(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceValidationService.getDeviceStatus(deviceId));
    }
}
