package com.aicabinet.trade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.aicabinet.trade.support.CacheService;

/**
 * 缓存配置与过期清理调度。
 * 每5分钟清理一次过期缓存条目。
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
