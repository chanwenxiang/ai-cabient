package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/**
 * 设备域 XXL-JOB 任务。
 * <p>开启 aicabinet.xxljob.enabled=true 后由调度中心调度；
 * 未开启时由 {@link DeviceAvailabilityJobScheduler} 内置调度兜底，无需额外部署。</p>
 */
@Component
public class DeviceXxlJobHandler {

    private final DeviceStableOnlineAutoUnlockService autoUnlockService;
    private final DeviceAvailabilityKpiService kpiService;

    public DeviceXxlJobHandler(DeviceStableOnlineAutoUnlockService autoUnlockService,
                               DeviceAvailabilityKpiService kpiService) {
        this.autoUnlockService = autoUnlockService;
        this.kpiService = kpiService;
    }

    /**
     * 稳定在线自动解锁（默认关闭，由系统参数 device.offline.auto_unlock_enabled 控制）。
     * 建议调度频率：每 5-10 分钟一次。
     */
    @XxlJob("deviceStableOnlineAutoUnlockJob")
    public void deviceStableOnlineAutoUnlock() {
        int count = autoUnlockService.autoUnlockStableOnlineDevices();
        XxlJobHelper.log("deviceStableOnlineAutoUnlockJob finished, unlocked={}", count);
        XxlJobHelper.handleSuccess("unlocked=" + count);
    }

    /**
     * 设备可用性 KPI 日快照（统计前一天）。
     * 建议调度频率：每日 01:00 后一次（cron 0 10 1 * * ?）。
     */
    @XxlJob("deviceAvailabilityKpiDailyJob")
    public void deviceAvailabilityKpiDaily() {
        DeviceAvailabilityKpiDto dto = kpiService.snapshotYesterday();
        XxlJobHelper.log("deviceAvailabilityKpiDailyJob finished, date={}", dto.kpiDate());
        XxlJobHelper.handleSuccess("date=" + dto.kpiDate());
    }
}
