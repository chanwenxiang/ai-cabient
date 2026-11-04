package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投放事件（曝光/完播/点击）的**服务端去重**：同一「设备 × 投放计划 × 素材 × 事件类型」
 * 在一个窗口内只记一次。
 *
 * <p><b>为什么需要它</b>：{@code ad_play_event} 是投放 ROI 的唯一数据来源，而此前**只有客户端
 * 去重**（{@code device-ad-banner.vue} 的 {@code impressed} Set / {@code completeTimers}）——
 * 客户端状态可被改包或脚本绕过，服务端对同一组合的重复上报**照单全收**。
 * 后果不是「被刷钱」（流量主模式下自有内容不收费），而是 **ROI 数据不可信**
 * （见 {@code docs/AD_MONETIZATION_DESIGN.md} §10 第 2 条）。
 *
 * <p><b>窗口为什么是 60 秒</b>：前端对同一素材的 IMPRESSION/COMPLETE 本来就**只报一次**，
 * 所以服务端窗口只需覆盖「重放与连点」，不需要长窗口。60s 与 {@code DoorEventDeduplicator}
 * 同口径，便于运维记忆。代价是用户在 60s 内刷新页面会少记一次曝光 —— 该行为本身接近刷量，可接受。
 *
 * <p><b>边界（写清楚，免得被当成「防刷万能药」）</b>：本去重只压「同一组合重复上报」，
 * **不防**遍历有限组合。但组合数 = 计划数 × 素材数，量级很小 ⇒ 遍历的最终效果等价于
 * 「正常看了几遍」，不会放大成任意倍数。**限流（每设备每分钟上限）刻意不做**：
 * 阈值没有实测依据，做出来只是拍脑袋的假保障。
 *
 * <p>Redis 不可用时回退本地内存（单实例有效、多实例失效）—— 与 {@code DoorEventDeduplicator}
 * 同策略：**宁可少挡，不可误挡**（把正常上报挡掉才是真的数据丢失）。
 */
@Component
public class AdPlayEventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(AdPlayEventDeduplicator.class);

    private static final long WINDOW_MS = 60_000L;
    private static final Duration WINDOW = Duration.ofMillis(WINDOW_MS);
    private static final String KEY_PREFIX = "aicabinet:ad-play-dedup:";
    /** 本地表清理阈值：超过该规模才遍历清理，避免每次调用都 O(n)。 */
    private static final int LOCAL_SWEEP_THRESHOLD = 256;

    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private volatile boolean redisAvailable = true;

    /** 无 Redis 环境（单元测试、或未配置 Redis 的部署）下的本地去重模式。 */
    public AdPlayEventDeduplicator() {
        this.redis = null;
    }

    /** 生产环境：Redis 可选（trade-service 里 Redis 并非强依赖，见 {@code CacheService}）。 */
    @Autowired
    public AdPlayEventDeduplicator(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
    }

    private boolean acquire(String key) {
        if (redis != null) {
            try {
                Boolean first = redis.opsForValue().setIfAbsent(key, "1", WINDOW);
                if (!redisAvailable) {
                    redisAvailable = true;
                    log.info("redis ad-play dedup recovered");
                }
                if (first != null) {
                    return first;
                }
            } catch (Exception e) {
                if (redisAvailable) {
                    redisAvailable = false;
                    log.warn("redis ad-play dedup unavailable, fallback to local: {}", e.toString());
                }
            }
        }
        return localTryAcquire(key);
    }

    private void releaseKey(String key) {
        if (redis != null) {
            try {
                redis.delete(key);
            } catch (Exception e) {
                log.debug("redis ad-play dedup release failed: {}", e.toString());
            }
        }
        recent.remove(key);
    }

    /**
     * 展示位打点的去重键。
     *
     * <p>subject 语义：小程序侧 = {@code U{userId}}（**按人去重**，此前是设备号 ⇒ 同一个人换 deviceId
     * 就能绕开 60s 窗口），设备/柜机屏侧 = 真实 deviceId。
     * position/activityId 参与键，是为了让「同一个活动位在开门前和关门后各报一次」不被互相吞掉。
     */
    public record PlayEventKey(String subject, String position, Long activityId, Long campaignId,
                               Long assetId, String eventType) {
    }

    /**
     * 申领一次「计量资格」。🔴 唯一入口是 {@link PlayEventKey} 形态：设备维度与「人」维度的键格式差异
     * 全部集中在 {@link #dedupKey(PlayEventKey)} 里。MA-150 之前这里另有一条按 deviceId 的四参重载，
     * 生产已无人调用却仍留着——留着就是「谁都能再走一遍不带人的去重」的诱惑，故删。
     */
    public boolean tryAcquire(PlayEventKey k) {
        String key = dedupKey(k);
        if (key == null) {
            return true; // 参数不全 ⇒ 与旧路径同策：放行而不是吞事件
        }
        return acquire(key);
    }

    /**
     * 归还资格：**落库失败时**调用，让同一窗口内的重试能重新落库
     * （否则一次 DB 抖动会让这次真实曝光在本窗口内彻底丢失）。best-effort，失败只记日志。
     */
    public void release(PlayEventKey k) {
        String key = dedupKey(k);
        if (key != null) {
            releaseKey(key);
        }
    }

    private static String dedupKey(PlayEventKey k) {
        if (k == null || k.subject() == null || k.subject().isBlank()
                || k.eventType() == null || k.eventType().isBlank()
                || (k.activityId() == null && k.campaignId() == null)) {
            return null;
        }
        if (k.position() == null && k.activityId() == null) {
            // 设备/柜机屏路径：逐字保持旧键格式，免得 60s 窗口内在途键失效
            if (k.campaignId() == null) {
                return null;
            }
            return KEY_PREFIX + k.subject() + ':' + k.campaignId()
                    + ':' + (k.assetId() == null ? "-" : k.assetId()) + ':' + k.eventType();
        }
        return KEY_PREFIX + k.subject() + ':' + k.position()
                + ':' + (k.activityId() == null ? "-" : k.activityId())
                + ':' + (k.campaignId() == null ? "-" : k.campaignId())
                + ':' + (k.assetId() == null ? "-" : k.assetId())
                + ':' + k.eventType();
    }

    /**
     * 本地单实例去重。
     *
     * <p>🔴 必须 {@code synchronized}：{@code get} 与 {@code put} 在 ConcurrentHashMap 上
     * **不是原子的**，并发下两个请求可能都读到「不存在」而双双通过 —— 那正好是本类要防的情况。
     */
    private synchronized boolean localTryAcquire(String key) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        Long previous = recent.get(key);
        if (previous != null && now - previous < WINDOW_MS) {
            return false;
        }
        recent.put(key, now);
        return true;
    }

    private void evictExpired(long now) {
        if (recent.size() < LOCAL_SWEEP_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= WINDOW_MS) {
                it.remove();
            }
        }
    }
}
