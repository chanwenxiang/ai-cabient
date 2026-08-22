package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateWarehouseTransferRequest;
import com.aicabinet.common.dto.WarehouseTransferDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.WarehouseTransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/warehouse/transfers")
public class WarehouseTransferController {

    private final WarehouseTransferService transferService;

    public WarehouseTransferController(WarehouseTransferService transferService) {
        this.transferService = transferService;
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:warehouse:edit"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<List<WarehouseTransferDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(transferService.list(operatorId(request), status));
    }

    @RequiresPermissions(value = {"ops:warehouse:list", "ops:warehouse:edit"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{transferId}")
    public ApiResponse<WarehouseTransferDto> detail(HttpServletRequest request, @PathVariable Long transferId) {
        return ApiResponse.ok(transferService.get(operatorId(request), transferId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping
    public ApiResponse<WarehouseTransferDto> create(
            HttpServletRequest request,
            @Valid @RequestBody CreateWarehouseTransferRequest body) {
        return ApiResponse.ok(transferService.create(operatorId(request), body));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/{transferId}/ship")
    public ApiResponse<WarehouseTransferDto> ship(HttpServletRequest request, @PathVariable Long transferId) {
        return ApiResponse.ok(transferService.ship(operatorId(request), transferId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/{transferId}/receive")
    public ApiResponse<WarehouseTransferDto> receive(HttpServletRequest request, @PathVariable Long transferId) {
        return ApiResponse.ok(transferService.receive(operatorId(request), transferId));
    }

    @RequiresPermissions("ops:warehouse:edit")
    @PostMapping("/{transferId}/cancel")
    public ApiResponse<WarehouseTransferDto> cancel(HttpServletRequest request, @PathVariable Long transferId) {
        return ApiResponse.ok(transferService.cancel(operatorId(request), transferId));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
