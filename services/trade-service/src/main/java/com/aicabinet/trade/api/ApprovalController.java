package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ApprovalDefinitionDto;
import com.aicabinet.common.dto.ApprovalInboxDto;
import com.aicabinet.common.dto.ApprovalTaskDto;
import com.aicabinet.common.dto.CreateApprovalDefinitionRequest;
import com.aicabinet.common.dto.UpsertApprovalDefinitionRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ApprovalWorkflowService;
import com.aicabinet.trade.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    @PostMapping("/definitions")
    public ApiResponse<ApprovalDefinitionDto> createDefinition(
            HttpServletRequest request,
            @Valid @RequestBody CreateApprovalDefinitionRequest body) {
        return ApiResponse.ok(approvalWorkflowService.createDefinition(operatorId(request), body));
    }

    @RequiresPermissions("ops:approval:config")
    @PutMapping("/definitions/{defId}")
    public ApiResponse<ApprovalDefinitionDto> updateDefinition(
            HttpServletRequest request,
            @PathVariable Long defId,
            @RequestBody UpsertApprovalDefinitionRequest body) {
        return ApiResponse.ok(approvalWorkflowService.updateDefinition(operatorId(request), defId, body));
    }

    @RequiresPermissions("ops:approval:config")
    @DeleteMapping("/definitions/{defId}")
    public ApiResponse<Void> deleteDefinition(HttpServletRequest request, @PathVariable Long defId) {
        approvalWorkflowService.deleteDefinition(operatorId(request), defId);
        return ApiResponse.ok(null);
    }

    /**
     * R9 拍板：后台手动发起审批——「新建审批流定义」零代码即可用（选定义+填业务单号直接建实例）。
     * 定义缺失/禁用/无节点 fail-loud 400；同单已有进行中实例 409。
     */
    @RequiresPermissions("ops:approval:config")
    @PostMapping("/instances")
    public ApiResponse<Map<String, Object>> startInstance(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        var instance = approvalWorkflowService.startManual(
                operatorId(request),
                body.get("bizType"),
                body.get("bizId"),
                body.get("title"));
        return ApiResponse.ok(Map.of(
                "instanceId", instance.getInstanceId(),
                "bizType", instance.getBizType(),
                "bizId", instance.getBizId(),
                "status", instance.getStatus(),
                "title", instance.getTitle() == null ? "" : instance.getTitle()));
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
