package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 缺货驱动自动建补货任务（CB-072 后续，2026-10-27 用户拍板「柜子缺货了应该就在补货任务里」）：
 * 定时扫描全部在用柜机，**按设置的阈值触发**——复用 {@link ReplenishmentService#suggestForDevice}
 * （fillToPar=false：台账库存 ≤ low_threshold、货道 ≤ minLevel/缺货阈值%、或销量再订货点），
 * 有缺口且该柜没有进行中任务时，按建议一键生成任务（路线 PLANNED + 任务 PENDING +
 * 有主仓时自动生成出库行，复用 {@link ReplenishmentService#createTaskFromSuggestion}）。
 *
 * <p>幂等口径：同柜已有 PENDING/IN_PROGRESS 任务即跳过——任务处理完再次跌破阈值才会再建。
 * 开关 {@code replenishment.auto_task.enabled}（默认开，运营台可关）；集群互斥走 Redis 锁。
 * operatorId=0（系统）留审计。每 30 分钟一轮（fixedDelay 串行不叠跑）。</p>
 */
@Component
public class AutoReplenishmentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AutoReplenishmentTaskService.class);

    static final String CONFIG_ENABLED = "replenishment.auto_task.enabled";
    private static final String LOCK_KEY = "aicabinet:lock:job:auto-replenish-task";
    private static final long LOCK_SECONDS = 30 * 60;

    private final DeviceInfoMapper deviceRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final ReplenishmentService replenishmentService;
    private final SystemConfigService systemConfigService;
    private final DistributedLockService distributedLockService;

    public AutoReplenishmentTaskService(DeviceInfoMapper deviceRepository,
                                        ReplenishmentTaskMapper taskRepository,
                                        ReplenishmentService replenishmentService,
                                        SystemConfigService systemConfigService,
                                        DistributedLockService distributedLockService) {
        this.deviceRepository = deviceRepository;
        this.taskRepository = taskRepository;
        this.replenishmentService = replenishmentService;
        this.systemConfigService = systemConfigService;
        this.distributedLockService = distributedLockService;
    }

    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 3 * 60_000L)
    public void scanAndCreate() {
        if (!systemConfigService.getBoolean(CONFIG_ENABLED, true)) {
            return;
        }
        if (!distributedLockService.tryLock(LOCK_KEY, LOCK_SECONDS, 0)) {
            return;
        }
        try {
            int created = 0;
            // @TableLogic 自动过滤已删除，无需显式 is_deleted 条件
            for (DeviceInfo device : deviceRepository.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<DeviceInfo>lambdaQuery())) {
                try {
                    if (createForDevice(device.getDeviceId())) {
                        created++;
                    }
                } catch (Exception e) {
                    log.warn("auto replenish task failed device={}: {}", device.getDeviceId(), e.toString());
                }
            }
            if (created > 0) {
                log.info("auto replenish tasks created={}", created);
            }
        } finally {
            distributedLockService.unlock(LOCK_KEY);
        }
    }

    /** @return true=本次为该柜创建了任务 */
    boolean createForDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        String dev = deviceId.trim();
        if (!taskRepository.findByDeviceIdAndStatusIn(
                dev, List.of("PENDING", "IN_PROGRESS")).isEmpty()) {
            return false;
        }
        boolean anyGap = replenishmentService.suggestForDevice(dev).stream()
                .anyMatch(s -> s.suggestQty() > 0);
        if (!anyGap) {
            return false;
        }
        ReplenishmentRouteDto route = replenishmentService.createTaskFromSuggestion(0L, dev, null);
        log.info("auto replenish task created device={} routeId={}", dev, route.routeId());
        return true;
    }
}
