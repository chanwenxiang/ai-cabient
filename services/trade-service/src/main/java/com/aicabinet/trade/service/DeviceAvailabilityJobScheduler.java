package com.aicabinet.trade.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 设备可用性相关任务的「内置调度」兜底。
 * <p>开启 XXL-JOB 后，经 {@link ScheduledTaskService#tryBegin} 对
 * {@link XxlJobManagedTasks} 自动让位；调度中心触发同一套方法。</p>
 */
@Component
public class DeviceAvailabilityJobScheduler {
    private static final String DEVICE_AUTO_UNLOCK = "device-auto-unlock";
    private static final String KPI_SNAPSHOT = "kpi-snapshot";


    private final DeviceStableOnlineAutoUnlockService autoUnlockService;
    private final DeviceAvailabilityKpiService kpiService;
    private final ScheduledTaskService taskService;

    public DeviceAvailabilityJobScheduler(DeviceStableOnlineAutoUnlockService autoUnlockService,
                                          DeviceAvailabilityKpiService kpiService,
                                          ScheduledTaskService taskService) {
        this.autoUnlockService = autoUnlockService;
        this.kpiService = kpiService;
        this.taskService = taskService;
    }

    /** 稳定在线自动解锁兜底：每 5 分钟扫描一次（是否动作由系统参数控制）。 */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void autoUnlockFallback() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(DEVICE_AUTO_UNLOCK, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无自动解锁";
        try {
            int n = autoUnlockService.autoUnlockStableOnlineDevices();
            summary = n <= 0 ? "本次无自动解锁" : "自动解锁 " + n + " 台";
        } catch (Exception e) {
            failed = true;
            taskService.finish(DEVICE_AUTO_UNLOCK, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(DEVICE_AUTO_UNLOCK, "SUCCESS", summary, start);
            }
        }
    }

    /** 设备可用性 KPI 日快照兜底：每日 1:10 统计前一天。 */
    @Scheduled(cron = "0 10 1 * * *")
    public void kpiSnapshotFallback() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(KPI_SNAPSHOT, 600)) {
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
            taskService.finish(KPI_SNAPSHOT, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(KPI_SNAPSHOT, "SUCCESS", summary, start);
            }
        }
    }
}
