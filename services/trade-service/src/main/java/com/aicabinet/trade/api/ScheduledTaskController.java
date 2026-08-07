package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.common.dto.ScheduledTaskRunResultDto;
import com.aicabinet.common.dto.ToggleScheduledTaskRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DistributedLockService;
import com.aicabinet.trade.service.ScheduledTaskRegistry;
import com.aicabinet.trade.service.ScheduledTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    @PutMapping("/{taskKey}/enabled")
    public ApiResponse<ScheduledTaskDto> setEnabled(HttpServletRequest request,
                                                    @PathVariable String taskKey,
                                                    @RequestBody ToggleScheduledTaskRequest body) {
        return ApiResponse.ok(taskService.setEnabled(operatorId(request), taskKey, body.enabled()));
    }

    @RequiresPermissions("ops:task:run")
    @PostMapping("/{taskKey}/run")
    public ApiResponse<ScheduledTaskRunResultDto> run(HttpServletRequest request,
                                                      @PathVariable String taskKey) {
        ScheduledTaskRegistry.TaskDescriptor descriptor = registry.get(taskKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey));
        if (descriptor.xxlManaged() && xxlJobEnabled) {
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "SKIPPED", "该任务已由 XXL-JOB 调度接管，请在调度中心执行"));
        }
        if (!taskService.isEnabled(taskKey)) {
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "SKIPPED", "任务已停用，请先在列表中启用"));
        }
        if (lockService.isLocked("job:" + taskKey)) {
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "SKIPPED", "任务正在执行中，请稍后再试"));
        }
        try {
            descriptor.action().run();
            auditService.record(operatorId(request), "SCHEDULED_TASK_RUN",
                    "SCHEDULED_TASK", taskKey, descriptor.name());
            return ApiResponse.ok(new ScheduledTaskRunResultDto(
                    taskKey, "TRIGGERED", "已触发执行，结果见列表最近执行列"));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "任务执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
