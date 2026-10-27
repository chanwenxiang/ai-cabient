package com.aicabinet.trade.support;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 集群会话纪元（JWT 的 {@code boot} claim 来源）。
 *
 * <p>2026-10-27 拍板 B：旧实现取**进程**启动时刻，代价是「trade 容器每重启一次，全员会话立即作废」——
 * refresh 宽限期（现 7 天，2026-10-24 拍板）被每次发版静默吃掉，补货员发版当天必重输密码；
 * 且 {@code JwtService.validateAndGetPrincipal} 同样校验 boot，多副本部署时 A 副本签发的 token
 * 到 B 副本恒 401。现在纪元落在 Redis（SETNX 语义，首个写入者定值），进程重启与多副本共用同一纪元。
 * 登出与 refresh 换发的即时失效本就以 Redis 的 jti 黑名单（{@code SessionRevocationService}）为准，不依赖本类，
 * 所以把 boot 从「进程」挪到「集群」没有放宽任何吊销语义。</p>
 *
 * <p>应急「全员下线」旋钮=删 {@code aicabinet:auth:boot-epoch} 这个键 ⇒ 下一次校验即重建为新纪元，
 * 旧 token 全部 boot 不匹配。为此这里**每次直读不回缓存**：同一条鉴权链上的 jti 校验本来就读 Redis，
 * 多一次 GET 不改变对 Redis 的依赖，换来的是旋钮即时生效、且不必为「读失败兜本地值」写兜底分支
 * （Redis 挂了这条链本就整体失败，兜出来的本地值只会让本进程与集群各定一个纪元）。</p>
 *
 * <p>⚠️ 删键的连带效应（已知且可接受）：{@code JwtService.revokeTokenQuietly} 也过 boot 校验，
 * 所以纪元切换后旧 token 的「登出」会静默不入 jti 黑名单——不影响收敛，因为它们的 boot 已经不匹配，
 * 业务接口与 refresh 都会直接拒绝。</p>
 */
@Component
public class ServerBootMarker {

    static final String EPOCH_KEY = "aicabinet:auth:boot-epoch";

    private final RedissonClient redisson;

    public ServerBootMarker(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public long epochMillis() {
        RBucket<String> bucket = redisson.getBucket(EPOCH_KEY, StringCodec.INSTANCE);
        long stored = parse(bucket.get());
        if (stored > 0L) {
            return stored;
        }
        long candidate = Instant.now().toEpochMilli();
        // trySet=SETNX：并发启动/多副本同时补键时只有第一个写入者定值，其余读到同一个纪元
        if (bucket.trySet(String.valueOf(candidate))) {
            return candidate;
        }
        long winner = parse(bucket.get());
        return winner > 0L ? winner : candidate;
    }

    /** 缺失/被人工写坏都按「没有纪元」处理——不能让一句脏值把鉴权面拖成异常。 */
    private static long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
