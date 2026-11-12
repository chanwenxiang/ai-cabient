package com.aicabinet.trade.service;

import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
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

    /**
     * 续租 Lua：仅当 Redisson 持有标记（hash field = clientId:threadId）仍在时重置 TTL。
     * owner 校验保证「锁已过期被其他实例抢走」时续租失败（返回 0），
     * 而不是把别人的锁续长——否则长任务挂死后会无限钉住锁、堵死自愈路径。
     */
    private static final String RENEW_LUA =
            "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then "
            + "return redis.call('pexpire', KEYS[1], ARGV[2]) "
            + "else return 0 end";

    private final RedissonClient redissonClient;

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

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
            // 锁由调用方 unlock；租约到期会自动释放。非本方法内临界区模式。
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS); // NOSONAR java:S2222

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

    /**
     * 续租：仅当锁仍由 {@code holderThreadId} 对应线程持有时，把 TTL 重置为 {@code leaseSeconds}。
     *
     * <p>供 {@code ScheduledTaskService} 的租约心跳调用（集群就绪 P-3）：心跳线程不是持锁线程，
     * 所以必须显式传持锁线程 id；Lua 内做 owner 校验，锁已过期/易主时返回 {@code false}
     * ——调用方应立即停止续租并按「锁已丢失」处理。</p>
     *
     * @return true = 续租成功；false = 锁不存在或已易主（或 Redis 异常，按失败处理）
     */
    public boolean renew(String lockKey, long leaseSeconds, long holderThreadId) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("租约时间必须大于 0");
        }
        String fullKey = LOCK_PREFIX + lockKey;
        String owner = redissonClient.getId() + ":" + holderThreadId;
        try {
            // 必须用 StringCodec：Redisson 锁内部 hash field 是裸 ASCII（LongCodec 写入），
            // 用全局 codec 会把 ARGV 编码成二进制，hexists 永不命中。
            Long r = redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RENEW_LUA,
                    RScript.ReturnType.INTEGER, Collections.singletonList(fullKey),
                    owner, String.valueOf(leaseSeconds * 1000));
            boolean ok = r != null && r > 0;
            if (ok) {
                log.debug("锁续租成功 key={} leaseSeconds={}", fullKey, leaseSeconds);
            } else {
                log.warn("锁续租失败（锁不存在或已易主）key={}", fullKey);
            }
            return ok;
        } catch (Exception e) {
            log.error("锁续租异常 key={}", fullKey, e);
            return false;
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
            // 返回 RLock 给调用方，由 releaseLock 释放；见 acquireLock/releaseLock 成对使用。
            boolean acquired = lock.tryLock(0, leaseTime, TimeUnit.SECONDS); // NOSONAR java:S2222
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
