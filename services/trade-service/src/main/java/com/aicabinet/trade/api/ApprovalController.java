package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ApprovalDefinitionDto;
import com.aicabinet.common.dto.ApprovalInboxDto;
import com.aicabinet.common.dto.ApprovalTaskDto;
import com.aicabinet.common.dto.UpsertApprovalDefinitionRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ApprovalWorkflowService;
import com.aicabinet.trade.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/approvals")
public class ApprovalController {

    private final ApprovalWorkflowService approvalWorkflowService;
    private final NotificationService notificationService;

    public ApprovalController(ApprovalWorkflowService approvalWorkflowService,
                                NotificationService notificationService) {
        this.approvalWorkflowService = approvalWorkflowService;
        this.notificationService = notificationService;
    }

    @RequiresPermissions(value = {"ops:approval:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/inbox")
    public ApiResponse<ApprovalInboxDto> inbox(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(approvalWorkflowService.inbox(operatorId(request), limit));
    }

    @RequiresPermissions(value = {"ops:approval:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/tasks/pending-count")
    public ApiResponse<Long> pendingCount(HttpServletRequest request) {
        return ApiResponse.ok(approvalWorkflowService.countPendingTasks(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:approval:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/tasks")
    public ApiResponse<List<ApprovalTaskDto>> pendingTasks(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(approvalWorkflowService.listPendingTasks(operatorId(request), limit));
    }

    @RequiresPermissions(value = {"ops:approval:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/tasks/{taskId}/read")
    public ApiResponse<Void> markTaskRead(HttpServletRequest request, @PathVariable Long taskId) {
        approvalWorkflowService.markTaskRead(operatorId(request), taskId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions(value = {"ops:approval:list", "ops:replenishment:list"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/messages/{id}/read")
    public ApiResponse<Void> markMessageRead(HttpServletRequest request, @PathVariable Long id) {
        notificationService.markOpsRead(operatorId(request), id);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:approval:config")
    @GetMapping("/definitions")
    public ApiResponse<List<ApprovalDefinitionDto>> definitions(HttpServletRequest request) {
        return ApiResponse.ok(approvalWorkflowService.listDefinitions(operatorId(request)));
    }

    @RequiresPermissions("ops:approval:config")
    @PutMapping("/definitions/{defId}")
    public ApiResponse<ApprovalDefinitionDto> updateDefinition(
            HttpServletRequest request,
            @PathVariable Long defId,
            @RequestBody UpsertApprovalDefinitionRequest body) {
        return ApiResponse.ok(approvalWorkflowService.updateDefinition(operatorId(request), defId, body));
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
