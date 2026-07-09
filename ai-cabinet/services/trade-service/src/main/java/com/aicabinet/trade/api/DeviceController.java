package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.trade.service.DeviceCatalogService;
import com.aicabinet.trade.service.DeviceValidationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/devices")
public class DeviceController {

    private final DeviceValidationService deviceValidationService;
    private final DeviceCatalogService deviceCatalogService;

    public DeviceController(DeviceValidationService deviceValidationService,
                            DeviceCatalogService deviceCatalogService) {
        this.deviceValidationService = deviceValidationService;
        this.deviceCatalogService = deviceCatalogService;
    }

    @GetMapping("/{deviceId}/status")
    public ApiResponse<DeviceStatusDto> status(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceValidationService.getDeviceStatus(deviceId));
    }

    @GetMapping("/{deviceId}/products")
    public ApiResponse<List<DeviceProductDto>> products(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceCatalogService.listProducts(deviceId));
    }
}
