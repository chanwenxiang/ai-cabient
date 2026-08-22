package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import com.xxl.job.core.context.XxlJobContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务管理：启停开关、分布式锁执行守卫、最近执行记录。
 * <p>所有写型定时任务通过 {@link #tryBegin}/{@link #finish} 包裹，保证集群下单实例执行，
 * 并在页面上可查看/启停/手动触发。</p>
 * <p>资金类等 {@link XxlJobManagedTasks}：开启 XXL-JOB 后内置 {@code @Scheduled} 自动让位，
 * 仅调度中心线程或 {@link #runAllowingBuiltin(Runnable)}（运营「立即执行」）可进入。</p>
 */
@Service
public class ScheduledTaskService {

    private static final ThreadLocal<Boolean> ALLOW_BUILTIN = new ThreadLocal<>();

    private final ScheduledTaskMapper taskRepository;
    private final DistributedLockService lockService;
    private final AdminAuditService auditService;
    private final boolean xxlJobEnabled;

    public ScheduledTaskService(ScheduledTaskMapper taskRepository,
                                DistributedLockService lockService,
                                AdminAuditService auditService,
                                @Value("${aicabinet.xxljob.enabled:false}") boolean xxlJobEnabled) {
        this.taskRepository = taskRepository;
        this.lockService = lockService;
        this.auditService = auditService;
        this.xxlJobEnabled = xxlJobEnabled;
    }

    @Transactional(readOnly = true)
    public List<ScheduledTaskDto> listAll() {
        return taskRepository.findAllByOrderByTaskKeyAsc().stream()
                .map(ScheduledTaskService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduledTaskDto get(String taskKey) {
        return toDto(requireTask(taskKey));
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String taskKey) {
        ScheduledTask row = taskRepository.selectById(taskKey);
        return row == null || Boolean.TRUE.equals(row.getEnabled());
    }

    @Transactional
    public ScheduledTaskDto setEnabled(Long operatorId, String taskKey, boolean enabled) {
        return runWithAdminTaskLock(taskKey, () -> doSetEnabled(operatorId, taskKey, enabled));
    }

    private ScheduledTaskDto doSetEnabled(Long operatorId, String taskKey, boolean enabled) {
        ScheduledTask row = requireTaskForUpdate(taskKey);
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.record(operatorId, enabled ? "SCHEDULED_TASK_ENABLE" : "SCHEDULED_TASK_DISABLE",
                "SCHEDULED_TASK", taskKey, row.getTaskName());
        return toDto(row);
    }

    /** 更新任务备注（说明这个任务干嘛的），留审计。 */
    @Transactional
    public ScheduledTaskDto setRemark(Long operatorId, String taskKey, String remark) {
        return runWithAdminTaskLock(taskKey, () -> doSetRemark(operatorId, taskKey, remark));
    }

    private ScheduledTaskDto doSetRemark(Long operatorId, String taskKey, String remark) {
        ScheduledTask row = requireTaskForUpdate(taskKey);
        row.setRemark(remark == null ? null : remark.trim());
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.record(operatorId, "SCHEDULED_TASK_REMARK", "SCHEDULED_TASK", taskKey, row.getTaskName());
        return toDto(row);
    }

    /**
     * 运营后台「立即执行」：即使任务已由 XXL 接管，也允许本进程跑一遍（仍走锁与执行记录）。
     */
    public void runAllowingBuiltin(Runnable action) {
        ALLOW_BUILTIN.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            ALLOW_BUILTIN.remove();
        }
    }

    /** 执行守卫：任务启用检查 + 分布式锁；返回是否允许本次执行。 */
    public boolean tryBegin(String taskKey, long leaseSeconds) {
        if (shouldYieldToXxlJob(taskKey)) {
            return false;
        }
        ScheduledTask row = taskRepository.selectById(taskKey);
        if (row != null && !Boolean.TRUE.equals(row.getEnabled())) {
            return false;
        }
        return lockService.tryLock("job:" + taskKey, leaseSeconds, 0);
    }

    /** 记录执行结果并释放分布式锁（独立事务，外层异常回滚不影响记录）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String taskKey, String result, String message, long startNanos) {
        ScheduledTask row = taskRepository.findByIdForUpdate(taskKey).orElse(null);
        if (row != null) {
            Instant now = Instant.now();
            row.setLastRunAt(now);
            row.setLastResult(result);
            row.setLastMessage(truncate(defaultMessage(result, message), 500));
            row.setLastDurationMs(Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L));
            row.setUpdatedAt(now);
            taskRepository.save(row);
        }
        lockService.unlock("job:" + taskKey);
    }

    /** XXL 已接管且当前不是 XXL 线程 / 运营强制执行时，内置调度让位。 */
    boolean shouldYieldToXxlJob(String taskKey) {
        if (!xxlJobEnabled || !XxlJobManagedTasks.isManaged(taskKey)) {
            return false;
        }
        if (Boolean.TRUE.equals(ALLOW_BUILTIN.get())) {
            return false;
        }
        return !invokedByXxlJob();
    }

    private static boolean invokedByXxlJob() {
        try {
            return XxlJobContext.getXxlJobContext() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 未传说明时的兜底；正常任务应写入本次处理条数/快照等结果。 */
    private static String defaultMessage(String result, String message) {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        if ("FAILED".equals(result)) {
            return "执行失败";
        }
        if ("SKIPPED".equals(result)) {
            return "已跳过";
        }
        return "本次无处理";
    }

    private ScheduledTask requireTask(String taskKey) {
        ScheduledTask row = taskRepository.selectById(taskKey);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey);
        }
        return row;
    }

    private ScheduledTask requireTaskForUpdate(String taskKey) {
        return taskRepository.findByIdForUpdate(taskKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey));
    }

    static String scheduledTaskAdminLockKey(String taskKey) {
        return "scheduled-task:admin:" + taskKey;
    }

    private <T> T runWithAdminTaskLock(String taskKey, java.util.function.Supplier<T> action) {
        String key = scheduledTaskAdminLockKey(taskKey);
        if (!lockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "定时任务配置处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            lockService.unlock(key);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static ScheduledTaskDto toDto(ScheduledTask row) {
        return new ScheduledTaskDto(
                row.getTaskKey(),
                row.getTaskName(),
                row.getTaskGroup(),
                row.getScheduleDesc(),
                Boolean.TRUE.equals(row.getEnabled()),
                row.getLastRunAt(),
                row.getLastResult(),
                row.getLastMessage(),
                row.getLastDurationMs(),
                row.getRemark());
    }
}
