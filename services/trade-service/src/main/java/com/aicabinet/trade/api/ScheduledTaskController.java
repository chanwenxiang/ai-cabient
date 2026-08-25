package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.common.dto.ScheduledTaskRunResultDto;
import com.aicabinet.common.dto.ToggleScheduledTaskRequest;
import com.aicabinet.common.dto.UpdateScheduledTaskMetaRequest;
import com.aicabinet.common.dto.UpdateScheduledTaskRemarkRequest;
import com.aicabinet.common.dto.UpsertScheduledTaskRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DistributedLockService;
import com.aicabinet.trade.service.ScheduledTaskRegistry;
import com.aicabinet.trade.service.ScheduledTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务管理：列表 / 启停 / 立即执行。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final ScheduledTaskRegistry registry;
    private final DistributedLockService lockService;
    private final AdminAuditService auditService;
    private final boolean xxlJobEnabled;

    public ScheduledTaskController(ScheduledTaskService taskService,
                                   ScheduledTaskRegistry registry,
                                   DistributedLockService lockService,
                                   AdminAuditService auditService,
                                   @Value("${aicabinet.xxljob.enabled:false}") boolean xxlJobEnabled) {
        this.taskService = taskService;
        this.registry = registry;
        this.lockService = lockService;
        this.auditService = auditService;
        this.xxlJobEnabled = xxlJobEnabled;
    }

    @RequiresPermissions("ops:task:list")
    @GetMapping
    public ApiResponse<List<ScheduledTaskDto>> list() {
        return ApiResponse.ok(taskService.listAll());
    }

    @RequiresPermissions("ops:task:edit")
    @PostMapping
    public ApiResponse<ScheduledTaskDto> create(HttpServletRequest request,
                                                @Valid @RequestBody UpsertScheduledTaskRequest body) {
        return ApiResponse.ok(taskService.create(operatorId(request), body));
    }

    @RequiresPermissions("ops:task:edit")
    @PutMapping("/{taskKey}")
    public ApiResponse<ScheduledTaskDto> updateMeta(HttpServletRequest request,
                                                    @PathVariable String taskKey,
                                                    @Valid @RequestBody UpdateScheduledTaskMetaRequest body) {
        return ApiResponse.ok(taskService.updateMeta(operatorId(request), taskKey, body));
    }

    @RequiresPermissions("ops:task:edit")
    @DeleteMapping("/{taskKey}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable String taskKey) {
        taskService.delete(operatorId(request), taskKey);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:task:edit")
    @PutMapping("/{taskKey}/enabled")
    public ApiResponse<ScheduledTaskDto> setEnabled(HttpServletRequest request,
                                                    @PathVariable String taskKey,
                                                    @RequestBody ToggleScheduledTaskRequest body) {
        return ApiResponse.ok(taskService.setEnabled(operatorId(request), taskKey, body.enabled()));
    }

    @RequiresPermissions("ops:task:edit")
    @PutMapping("/{taskKey}/remark")
    public ApiResponse<ScheduledTaskDto> updateRemark(HttpServletRequest request,
                                                      @PathVariable String taskKey,
                                                      @RequestBody UpdateScheduledTaskRemarkRequest body) {
        return ApiResponse.ok(taskService.setRemark(operatorId(request), taskKey, body.remark()));
    }

    @RequiresPermissions("ops:task:run")
    @PostMapping("/{taskKey}/run")
    public ApiResponse<ScheduledTaskRunResultDto> run(HttpServletRequest request,
                                                      @PathVariable String taskKey) {
        ScheduledTaskRegistry.TaskDescriptor descriptor = registry.get(taskKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey));
        if (!taskService.isEnabled(taskKey)) {
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "SKIPPED", "任务已停用，请先在列表中启用"));
        }
        if (lockService.isLocked("job:" + taskKey)) {
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "SKIPPED", "任务正在执行中，请稍后再试"));
        }
        try {
            Instant beforeRun = taskService.get(taskKey).lastRunAt();
            // XXL 托管任务：运营「立即执行」仍走本进程，经 runAllowingBuiltin 绕过内置让位
            taskService.runAllowingBuiltin(descriptor.action());
            auditService.record(operatorId(request), "SCHEDULED_TASK_RUN",
                    "SCHEDULED_TASK", taskKey, descriptor.name());
            ScheduledTaskDto after = taskService.get(taskKey);
            if (after.lastRunAt() == null
                    || (beforeRun != null && !after.lastRunAt().isAfter(beforeRun))) {
                String hint = descriptor.xxlManaged() && xxlJobEnabled
                        ? "任务未写入执行记录（可能未抢到锁；也可在 XXL-JOB 控制台触发）"
                        : "任务未写入执行记录（可能未抢到锁或提前返回）";
                return ApiResponse.ok(new ScheduledTaskRunResultDto(taskKey, "SKIPPED", hint));
            }
            String detail = after.lastMessage() == null || after.lastMessage().isBlank()
                    ? "已执行"
                    : after.lastMessage();
            String duration = after.lastDurationMs() == null ? "—" : after.lastDurationMs() + " ms";
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey,
                    "TRIGGERED",
                    detail + "（耗时 " + duration + "）",
                    after.lastMessage(),
                    after.lastDurationMs()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "任务执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
