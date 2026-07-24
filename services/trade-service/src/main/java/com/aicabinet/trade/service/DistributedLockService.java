package com.aicabinet.trade.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁。
 * <ul>
 *   <li>必须设置有限租约，持有方宕机后锁可自动释放（BE-003）。</li>
 *   <li>仅当当前线程持有锁时才解锁。</li>
 *   <li>{@link #forceUnlock} 仅用于运维恢复，业务路径勿用。</li>
 * </ul>
 */
@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private static final String LOCK_PREFIX = "aicabinet:lock:";

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 尝试获取锁。
     *
     * @param lockKey   业务键（自动加前缀）
     * @param leaseTime 租约秒数，到期自动释放
     * @param waitTime  等待获取的秒数
     * @return 是否获取成功
     */
    public boolean tryLock(String lockKey, long leaseTime, long waitTime) {
        if (leaseTime <= 0) {
            throw new IllegalArgumentException("租约时间必须大于 0，否则可能死锁");
        }
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);

        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (acquired) {
                log.debug("加锁成功 key={} leaseSeconds={}", fullKey, leaseTime);
            } else {
                log.warn("加锁失败 key={}", fullKey);
            }

            return acquired;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("加锁被中断 key={}", fullKey, e);
            return false;
        }
    }

    /** 不等待，立即尝试加锁。 */
    public boolean tryLock(String lockKey, long leaseTime) {
        return tryLock(lockKey, leaseTime, 0);
    }

    /** 当前线程持有时释放锁。 */
    public void unlock(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);

        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("解锁成功 key={}", fullKey);
        } else {
            log.warn("解锁跳过：当前线程未持有 key={}", fullKey);
        }
    }

    public boolean isLocked(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        return lock.isLocked();
    }

    public boolean isHeldByCurrentThread(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        return lock.isHeldByCurrentThread();
    }

    /** 强制解锁，仅运维恢复使用。 */
    public void forceUnlock(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);

        if (lock.isLocked()) {
            lock.forceUnlock();
            log.warn("强制解锁 key={}", fullKey);
        }
    }

    /** 获取锁对象；失败返回 null。 */
    public RLock acquireLock(String lockKey, long leaseTime) {
        if (leaseTime <= 0) {
            throw new IllegalArgumentException("租约时间必须大于 0，否则可能死锁");
        }
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        try {
            boolean acquired = lock.tryLock(0, leaseTime, TimeUnit.SECONDS);
            if (acquired) {
                return lock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** 释放由 {@link #acquireLock} 取得的锁。 */
    public void releaseLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
