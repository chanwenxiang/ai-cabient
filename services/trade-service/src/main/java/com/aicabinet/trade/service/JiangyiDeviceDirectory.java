package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将邑设备判定（CB-022）：deviceId 是否为「已绑定」的将邑柜。
 * <p>开门路由（DeviceServiceClient）据此分流：将邑柜走 gateway，其余走既有
 * device-service 链路。判定 = jiangyi_device 存在记录且 status=BOUND
 * （UNBOUND 未绑定不可路由，RETIRED 退役拒绝路由）。</p>
 *
 * <p>trade-service 未引入 Caffeine，用进程内 ConcurrentHashMap TTL 缓存：
 * 命中 60s / 未命中 10s（避免高频负查询打库），绑定/退役/登记时由
 * {@link JiangyiOnboardingService} 主动 {@link #evict}。</p>
 */
@Service
public class JiangyiDeviceDirectory {

    private static final Logger log = LoggerFactory.getLogger(JiangyiDeviceDirectory.class);

    private static final long POSITIVE_TTL_MS = 60_000L;
    private static final long NEGATIVE_TTL_MS = 10_000L;

    private final JiangyiDeviceMapper jiangyiDeviceMapper;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(boolean bound, long expiresAtMs) {}

    public JiangyiDeviceDirectory(JiangyiDeviceMapper jiangyiDeviceMapper) {
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
    }

    /** true = 该柜机是已绑定的将邑开门柜（开门指令应路由到 jiangyi-gateway）。 */
    public boolean isJiangyi(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(deviceId);
        if (entry != null && entry.expiresAtMs() > now) {
            return entry.bound();
        }
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        boolean bound = device != null && "BOUND".equals(device.getStatus());
        long ttl = bound ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
        cache.put(deviceId, new CacheEntry(bound, now + ttl));
        return bound;
    }

    /** 登记/绑定/退役后主动失效，保证路由判定即时生效。 */
    public void evict(String deviceId) {
        if (deviceId != null) {
            cache.remove(deviceId);
            log.info("jiangyi device directory evicted deviceId={}", deviceId);
        }
    }
}
