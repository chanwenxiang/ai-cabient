package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceDataFeeBillDto;
import com.aicabinet.common.dto.GenerateMonthlyFeeBillsRequest;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DeviceDataFeeBillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class DeviceDataFeeBillController {

    private final DeviceDataFeeBillService billService;

    public DeviceDataFeeBillController(DeviceDataFeeBillService billService) {
        this.billService = billService;
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping("/device-data-fee-bills")
    public ApiResponse<PageResult<DeviceDataFeeBillDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String billMonth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(billService.list(operatorId(request), billMonth, status, deviceId, page, size));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/devices/{deviceId}/data-fee-bills/generate")
    public ApiResponse<DeviceDataFeeBillDto> generateOne(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody(required = false) GenerateMonthlyFeeBillsRequest body) {
        return ApiResponse.ok(billService.generateForDevice(operatorId(request), deviceId,
                body == null ? new GenerateMonthlyFeeBillsRequest(null) : body));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/device-data-fee-bills/generate")
    public ApiResponse<List<DeviceDataFeeBillDto>> generateAll(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) GenerateMonthlyFeeBillsRequest body) {
        return ApiResponse.ok(billService.generateForAllCharged(operatorId(request),
                body == null ? new GenerateMonthlyFeeBillsRequest(null) : body));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/device-data-fee-bills/{billId}/pay")
    public ApiResponse<DeviceDataFeeBillDto> markPaid(HttpServletRequest request, @PathVariable Long billId) {
        return ApiResponse.ok(billService.markPaid(operatorId(request), billId));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/device-data-fee-bills/{billId}/void")
    public ApiResponse<DeviceDataFeeBillDto> voidBill(HttpServletRequest request, @PathVariable Long billId) {
        return ApiResponse.ok(billService.voidBill(operatorId(request), billId));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
