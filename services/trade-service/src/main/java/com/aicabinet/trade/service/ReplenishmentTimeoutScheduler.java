package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 已签到补货任务长时间未完成会挡消费者开门；超时自动关会话并终态收口。
 */
@Service
public class ReplenishmentTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentTimeoutScheduler.class);
    /** 签到后超过该小时仍 IN_PROGRESS 则收口。 */
    private static final long STALE_CHECK_IN_HOURS = 4;

    private final ReplenishmentTaskMapper taskRepository;
    private final ReplenishmentTaskLineMapper taskLineRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final SessionService sessionService;
    private final OpsExceptionService opsExceptionService;

    @Autowired
    private ScheduledTaskService taskService;

    public ReplenishmentTimeoutScheduler(ReplenishmentTaskMapper taskRepository,
                                         ReplenishmentTaskLineMapper taskLineRepository,
                                         ReplenishmentRouteMapper routeRepository,
                                         SessionService sessionService,
                                         OpsExceptionService opsExceptionService) {
        this.taskRepository = taskRepository;
        this.taskLineRepository = taskLineRepository;
        this.routeRepository = routeRepository;
        this.sessionService = sessionService;
        this.opsExceptionService = opsExceptionService;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleCheckedInTasks() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("replenishment-timeout", 600)) {
            return;
        }
        boolean failed = false;
        try {
        Instant cutoff = Instant.now().minus(STALE_CHECK_IN_HOURS, ChronoUnit.HOURS);
        List<ReplenishmentTask> stale = taskRepository.findByStatusAndCheckInAtBefore(
                "IN_PROGRESS", cutoff, 100);
        if (stale.isEmpty()) {
            return;
        }
        int n = 0;
        for (ReplenishmentTask task : stale) {
            try {
                expireOne(task);
                n++;
            } catch (Exception e) {
                log.warn("expire stale replenishment task failed taskId={}", task.getTaskId(), e);
            }
        }
        if (n > 0) {
            log.info("expired stale checked-in replenishment tasks count={}", n);
        }
        } catch (Exception e) {
            failed = true;
            taskService.finish("replenishment-timeout", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("replenishment-timeout", "SUCCESS", null, start);
            }
        }
    }

    private void expireOne(ReplenishmentTask task) {
        Long taskId = task.getTaskId();
        sessionService.closeRestockSessionsForTask(taskId, "补货任务超时自动关闭会话");

        boolean hasApplied = taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()
                .anyMatch(ReplenishmentTaskLine::isApplied);

        String note = "签到超过" + STALE_CHECK_IN_HOURS + "小时未完成，系统自动取消以恢复售卖";
        task.setStatus("CANCELLED");
        String prev = task.getNotes();
        task.setNotes(prev == null || prev.isBlank() ? note : (prev + "；" + note));
        taskRepository.save(task);
        finalizeRouteIfReady(task.getRouteId());

        opsExceptionService.report(
                "RESTOCK_TASK_TIMEOUT",
                hasApplied ? "HIGH" : "MEDIUM",
                task.getDeviceId(),
                null,
                null,
                null,
                "补货任务超时",
                "任务 #" + taskId + " 已签到超时自动取消"
                        + (hasApplied ? "（存在已上架明细，请核对库存）" : ""));
        log.warn("stale replenishment task cancelled taskId={} device={} hasApplied={}",
                taskId, task.getDeviceId(), hasApplied);
    }

    private void finalizeRouteIfReady(Long routeId) {
        if (routeId == null) {
            return;
        }
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        if (routeTasks.isEmpty()) {
            return;
        }
        boolean allTerminal = routeTasks.stream()
                .allMatch(item -> "COMPLETED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus()));
        if (!allTerminal) {
            return;
        }
        boolean anyCompleted = routeTasks.stream().anyMatch(item -> "COMPLETED".equals(item.getStatus()));
        routeRepository.findById(routeId).ifPresent(route -> {
            route.setStatus(anyCompleted ? "COMPLETED" : "CANCELLED");
            routeRepository.save(route);
        });
    }
}
