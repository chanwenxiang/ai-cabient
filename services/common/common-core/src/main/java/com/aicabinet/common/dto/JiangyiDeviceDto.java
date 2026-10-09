package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 将邑设备绑定档案（CB-022 模式一，运营后台展示用）。
 *
 * <p>三元组：我方 device_id ↔ 将邑 device_sn（工控机 SN）↔ identifier（将邑签发编码）。
 * status：UNBOUND（已登记未绑定）/ BOUND（可路由）/ RETIRED（退役拒绝一切路由）。
 * tokenVersion/tokenIssuedAt 供运维诊断 token 吊销状态；lastWsOnlineAt 即设备最近在线时刻。</p>
 */
public record JiangyiDeviceDto(
        String deviceId,
        String deviceSn,
        String identifier,
        String modelName,
        String classesVersion,
        String status,
        Long tokenVersion,
        Instant tokenIssuedAt,
        Instant lastWsOnlineAt,
        Instant createdAt,
        Instant updatedAt
) {}
