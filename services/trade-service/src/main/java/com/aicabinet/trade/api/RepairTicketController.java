package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RepairTicketDetailDto;
import com.aicabinet.common.dto.RepairTicketDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.RepairTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/repair-tickets")
public class RepairTicketController {
    private static final String ASSIGNEE = "assignee";
    private static final String REMARK = "remark";


    private final RepairTicketService repairTicketService;

    private final AdminAuditService auditService;

    public RepairTicketController(RepairTicketService repairTicketService, AdminAuditService auditService) {
        this.repairTicketService = repairTicketService;
        this.auditService = auditService;
    }

    @RequiresPermissions(value = {"ops:repair:list", "ops:device:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<RepairTicketDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String faultType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(repairTicketService.list(
                operator(request), status, deviceId, priority, faultType, page, size));
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
        Long operatorId = operator(request);
        RepairTicketDto created = repairTicketService.create(
                operatorId,
                body.get("deviceId"),
                body.get("title"),
                body.get("faultType"),
                body.get(ASSIGNEE),
                body.get("priority"),
                body.get(REMARK));
        auditService.appendLog(operatorId, "REPAIR_TICKET_CREATE", "REPAIR_TICKET",
                created == null ? null : String.valueOf(created.ticketId()),
                "设备=" + body.get("deviceId") + " 标题=" + body.get("title") + " 优先级=" + body.get("priority"));
        return ApiResponse.ok(created);
    }

    @RequiresPermissions("ops:repair:edit")
    @PatchMapping("/{ticketId}")
    public ApiResponse<RepairTicketDto> update(
            HttpServletRequest request,
            @PathVariable long ticketId,
            @RequestBody Map<String, String> body) {
        Long operatorId = operator(request);
        RepairTicketDto updated = repairTicketService.update(
                operatorId,
                ticketId,
                body.get("title"),
                body.get("faultType"),
                body.get(ASSIGNEE),
                body.get("priority"),
                body.get(REMARK));
        auditService.appendLog(operatorId, "REPAIR_TICKET_UPDATE", "REPAIR_TICKET",
                String.valueOf(ticketId), "标题=" + body.get("title") + " 优先级=" + body.get("priority"));
        return ApiResponse.ok(updated);
    }

    @RequiresPermissions("ops:repair:edit")
    @PostMapping("/batch-assign")
    public ApiResponse<Integer> batchAssign(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.getOrDefault("ticketIds", List.of());
        List<Long> ids;
        try {
            ids = rawIds.stream()
                    .map(o -> Long.valueOf(String.valueOf(o)))
                    .toList();
        } catch (NumberFormatException e) {
            // MA-113：非数字 ticketIds 此前 NumberFormatException→500
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds 必须是数字");
        }
        Object assignee = body.get(ASSIGNEE);
        Long operatorId = operator(request);
        int count = repairTicketService.batchAssign(
                operatorId,
                ids,
                // MA-113：{"assignee": null} 经 String.valueOf 会变成字面量 "null" 入库
                assignee == null ? "" : String.valueOf(assignee));
        auditService.appendLog(operatorId, "REPAIR_TICKET_BATCH_ASSIGN", "REPAIR_TICKET",
                "batch", "批量指派 " + count + " 单 给=" + (assignee == null ? "" : assignee));
        return ApiResponse.ok(count);
    }

    @RequiresPermissions("ops:repair:edit")
    @PostMapping("/{ticketId}/transition")
    public ApiResponse<RepairTicketDto> transition(
            HttpServletRequest request,
            @PathVariable long ticketId,
            @RequestBody Map<String, String> body) {
        Long operatorId = operator(request);
        RepairTicketDto transitioned = repairTicketService.transition(
                operatorId, ticketId, body.get("status"), body.get(REMARK),
                "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("unlockDevice", "false"))));
        auditService.appendLog(operatorId, "REPAIR_TICKET_TRANSITION", "REPAIR_TICKET",
                String.valueOf(ticketId), "状态=" + body.get("status")
                        + " 解锁设备=" + String.valueOf(body.getOrDefault("unlockDevice", "false")));
        return ApiResponse.ok(transitioned);
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
