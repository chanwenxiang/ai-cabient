package com.aicabinet.trade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.trade.support.CacheService;

/**
 * 缓存配置与过期清理调度。
 * <p>每 5 分钟清理本进程的过期缓存条目。</p>
 */
@Configuration
@EnableScheduling
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    private final CacheService cacheService;

    public CacheConfig(CacheService cacheService) {
        this.cacheService = cacheService;
        log.info("CacheService initialized (local TTL cache)");
    }

    /**
     * 清理本进程过期缓存条目（本机资源回收）。
     *
     * <p><b>刻意不经 tryBegin 抢分布式锁。</b>清理对象是本进程的 {@code ConcurrentHashMap}
     * （{@code CacheService#purgeExpired}），多实例下每个实例都必须清理自己那一份。
     * 早先借全局锁的写法会让集群里只有一台实例被清，其余实例的内存条目<em>永不回收</em>
     * —— 借锁在这里不是「防重复」，而是「漏清理」。</p>
     *
     * <p><b>也不由 XXL-JOB 托管</b>：本机资源回收不应依赖外部调度中心的可用性
     * （调度中心挂掉时，本地缓存仍应正常回收）。它因此不属于「业务定时任务」：
     * 不进 {@code scheduled_task} 台账、运营台不可见、不参与超期看护。
     * 执行情况由 Micrometer 自动打点（{@code tasks_scheduled_execution_seconds_count}）覆盖。</p>
     */
    @Scheduled(fixedRate = 300_000)
    public void purgeExpiredCache() {
        int before = cacheService.totalSize();
        cacheService.purgeExpired();
        int after = cacheService.totalSize();
        if (before != after) {
            log.debug("cache purge: {} -> {} items", before, after);
        }
    }
}
