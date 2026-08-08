package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 按手机号统计登录失败次数（Redis 计数，过期自动清零）。
 * 与网关 IP 限流互补：防撞库/爆破针对账户维度，不误伤同网用户。
 */
@Service
public class LoginThrottleService {

    private static final String KEY_PREFIX = "aicabinet:login:fail:";

    private final RedissonClient redisson;
    private final AuthProperties authProperties;

    public LoginThrottleService(RedissonClient redisson, AuthProperties authProperties) {
        this.redisson = redisson;
        this.authProperties = authProperties;
    }

    /** 记录一次失败；达到上限返回锁定提示，否则返回 empty。 */
    public Optional<String> recordFailure(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        RAtomicLong counter = redisson.getAtomicLong(KEY_PREFIX + phone);
        long attempts = counter.incrementAndGet();
        if (attempts == 1) {
            counter.expire(Duration.ofMinutes(lockMinutes()));
        }
        if (attempts >= maxFailures()) {
            return Optional.of("登录失败次数过多，请 " + lockMinutes() + " 分钟后再试");
        }
        return Optional.empty();
    }

    /** 登录成功后清零。 */
    public void clearFailures(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        redisson.getAtomicLong(KEY_PREFIX + phone).delete();
    }

    private int maxFailures() {
        return authProperties.loginMaxFailures() > 0 ? authProperties.loginMaxFailures() : 5;
    }

    private int lockMinutes() {
        return authProperties.loginLockMinutes() > 0 ? authProperties.loginLockMinutes() : 10;
    }
}
