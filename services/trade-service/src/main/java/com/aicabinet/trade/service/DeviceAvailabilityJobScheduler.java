package com.aicabinet.trade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 设备可用性相关任务的「内置调度」兜底：
 * <p>默认开箱即用，无需部署 XXL-JOB；当 {@code aicabinet.xxljob.enabled=true}
 * （XXL-JOB 接管）时本调度自动让位，由调度中心统一触发，避免双跑。</p>
 */
@Component
public class DeviceAvailabilityJobScheduler {

    private final DeviceStableOnlineAutoUnlockService autoUnlockService;
    private final DeviceAvailabilityKpiService kpiService;
    private final ScheduledTaskService taskService;
    private final boolean xxlJobEnabled;

    public DeviceAvailabilityJobScheduler(DeviceStableOnlineAutoUnlockService autoUnlockService,
                                          DeviceAvailabilityKpiService kpiService,
                                          ScheduledTaskService taskService,
                                          @Value("${aicabinet.xxljob.enabled:false}") boolean xxlJobEnabled) {
        this.autoUnlockService = autoUnlockService;
        this.kpiService = kpiService;
        this.taskService = taskService;
        this.xxlJobEnabled = xxlJobEnabled;
    }

    /** 稳定在线自动解锁兜底：每 5 分钟扫描一次（是否动作由系统参数控制）。 */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void autoUnlockFallback() {
        if (xxlJobEnabled) {
            return;
        }
        long start = System.nanoTime();
        if (!taskService.tryBegin("device-auto-unlock", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无自动解锁";
        try {
            int n = autoUnlockService.autoUnlockStableOnlineDevices();
            summary = n <= 0 ? "本次无自动解锁" : "自动解锁 " + n + " 台";
        } catch (Exception e) {
            failed = true;
            taskService.finish("device-auto-unlock", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("device-auto-unlock", "SUCCESS", summary, start);
            }
        }
    }

    /** 设备可用性 KPI 日快照兜底：每日 1:10 统计前一天。 */
    @Scheduled(cron = "0 10 1 * * *")
    public void kpiSnapshotFallback() {
        if (xxlJobEnabled) {
            return;
        }
        long start = System.nanoTime();
        if (!taskService.tryBegin("kpi-snapshot", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次未写入可用性 KPI";
        try {
            var dto = kpiService.snapshotYesterday();
            summary = "已写入 " + dto.kpiDate() + " 可用性快照，设备 " + dto.deviceTotal()
                    + " 台，离线事件 " + dto.offlineEvents() + "，自动锁机 " + dto.autoLockCount();
        } catch (Exception e) {
            failed = true;
            taskService.finish("kpi-snapshot", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("kpi-snapshot", "SUCCESS", summary, start);
            }
        }
    }
}
