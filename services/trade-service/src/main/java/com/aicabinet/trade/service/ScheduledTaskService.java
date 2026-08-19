package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
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
 */
@Service
public class ScheduledTaskService {

    private final ScheduledTaskMapper taskRepository;
    private final DistributedLockService lockService;
    private final AdminAuditService auditService;

    public ScheduledTaskService(ScheduledTaskMapper taskRepository,
                                DistributedLockService lockService,
                                AdminAuditService auditService) {
        this.taskRepository = taskRepository;
        this.lockService = lockService;
        this.auditService = auditService;
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
        ScheduledTask row = requireTask(taskKey);
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
        ScheduledTask row = requireTask(taskKey);
        row.setRemark(remark == null ? null : remark.trim());
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.record(operatorId, "SCHEDULED_TASK_REMARK", "SCHEDULED_TASK", taskKey, row.getTaskName());
        return toDto(row);
    }

    /** 执行守卫：任务启用检查 + 分布式锁；返回是否允许本次执行。 */
    public boolean tryBegin(String taskKey, long leaseSeconds) {
        ScheduledTask row = taskRepository.selectById(taskKey);
        if (row != null && !Boolean.TRUE.equals(row.getEnabled())) {
            return false;
        }
        return lockService.tryLock("job:" + taskKey, leaseSeconds, 0);
    }

    /** 记录执行结果并释放分布式锁（独立事务，外层异常回滚不影响记录）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String taskKey, String result, String message, long startNanos) {
        ScheduledTask row = taskRepository.selectById(taskKey);
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
