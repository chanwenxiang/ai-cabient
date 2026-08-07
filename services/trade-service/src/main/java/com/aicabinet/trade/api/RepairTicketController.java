package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RepairTicketDetailDto;
import com.aicabinet.common.dto.RepairTicketDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.RepairTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/repair-tickets")
public class RepairTicketController {

    private final RepairTicketService repairTicketService;

    public RepairTicketController(RepairTicketService repairTicketService) {
        this.repairTicketService = repairTicketService;
    }

    @RequiresPermissions(value = {"ops:repair:list", "ops:device:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<RepairTicketDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(repairTicketService.list(operator(request), status, deviceId, priority, page, size));
    }

    @RequiresPermissions(value = {"ops:repair:list", "ops:device:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/by-device/{deviceId}")
    public ApiResponse<List<RepairTicketDto>> byDevice(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(repairTicketService.listByDevice(operator(request), deviceId, limit));
    }

    @RequiresPermissions(value = {"ops:repair:list", "ops:device:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{ticketId}")
    public ApiResponse<RepairTicketDetailDto> detail(HttpServletRequest request, @PathVariable long ticketId) {
        return ApiResponse.ok(repairTicketService.detail(operator(request), ticketId));
    }

    @RequiresPermissions("ops:repair:edit")
    @PostMapping
    public ApiResponse<RepairTicketDto> create(HttpServletRequest request, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(repairTicketService.create(
                operator(request),
                body.get("deviceId"),
                body.get("title"),
                body.get("faultType"),
                body.get("assignee"),
                body.get("priority"),
                body.get("remark")));
    }

    @RequiresPermissions("ops:repair:edit")
    @PatchMapping("/{ticketId}")
    public ApiResponse<RepairTicketDto> update(
            HttpServletRequest request,
            @PathVariable long ticketId,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(repairTicketService.update(
                operator(request),
                ticketId,
                body.get("title"),
                body.get("faultType"),
                body.get("assignee"),
                body.get("priority"),
                body.get("remark")));
    }

    @RequiresPermissions("ops:repair:edit")
    @PostMapping("/batch-assign")
    public ApiResponse<Integer> batchAssign(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.getOrDefault("ticketIds", List.of());
        List<Long> ids = rawIds.stream()
                .map(o -> Long.valueOf(String.valueOf(o)))
                .toList();
        return ApiResponse.ok(repairTicketService.batchAssign(
                operator(request),
                ids,
                String.valueOf(body.getOrDefault("assignee", ""))));
    }

    @RequiresPermissions("ops:repair:edit")
    @PostMapping("/{ticketId}/transition")
    public ApiResponse<RepairTicketDto> transition(
            HttpServletRequest request,
            @PathVariable long ticketId,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(repairTicketService.transition(
                operator(request), ticketId, body.get("status"), body.get("remark"),
                "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("unlockDevice", "false")))));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
