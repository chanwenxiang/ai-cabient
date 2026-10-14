package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceSkuPricingRowDto;
import com.aicabinet.common.dto.UpdateDeviceSkuPriceRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsDevicePricingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * admin 设备详情页「单柜定价」（V350，CB-037）。
 *
 * <p>查看沿用设备详情面板既有口径 {@code ops:device:list}（铁律 66：新增能力优先沿用
 * 承载面板既有口径）；改价是资金敏感操作，走独立新权限点 {@code ops:pricing:edit}
 * （与商户端 merchant:pricing:edit 对称，2026-10-14 用户拍板）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class AdminDevicePricingController {

    private final OpsDevicePricingService devicePricingService;

    public AdminDevicePricingController(OpsDevicePricingService devicePricingService) {
        this.devicePricingService = devicePricingService;
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/pricing")
    public ApiResponse<List<DeviceSkuPricingRowDto>> devicePricing(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(devicePricingService.listPricing(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:pricing:edit")
    @PutMapping("/devices/{deviceId}/pricing/{skuId}")
    public ApiResponse<DeviceSkuPricingRowDto> updateDeviceSkuPrice(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @PathVariable("skuId") String skuId,
            @Valid @RequestBody UpdateDeviceSkuPriceRequest body) {
        return ApiResponse.ok(
                devicePricingService.updatePricing(operatorId(request), deviceId, skuId, body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
