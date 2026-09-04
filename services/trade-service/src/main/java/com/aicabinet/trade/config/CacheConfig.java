package com.aicabinet.trade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.trade.service.ScheduledTaskService;
import com.aicabinet.trade.support.CacheService;

/**
 * 缓存配置与过期清理调度。
 * 每5分钟清理一次过期缓存条目（经 tryBegin 保证多实例不重复扫）。
 */
@Configuration
@EnableScheduling
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    private static final String TASK_KEY = "cache-purge";

    private final CacheService cacheService;
    private final ScheduledTaskService taskService;

    public CacheConfig(CacheService cacheService, ScheduledTaskService taskService) {
        this.cacheService = cacheService;
        this.taskService = taskService;
        log.info("CacheService initialized (local TTL cache)");
    }

    @Scheduled(fixedRate = 300_000)
    public void purgeExpiredCache() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 300)) {
            return;
        }
        try {
            int before = cacheService.totalSize();
            cacheService.purgeExpired();
            int after = cacheService.totalSize();
            if (before != after) {
                log.debug("cache purge: {} -> {} items", before, after);
            }
            taskService.finish(TASK_KEY, "SUCCESS",
                    "清理前 " + before + " / 清理后 " + after, start);
        } catch (Exception e) {
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            throw e;
        }
    }
}
