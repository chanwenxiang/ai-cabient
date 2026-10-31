package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * G2-11 ⑦：某商户下已授权的运营账号（按商户反查）。
 *
 * <p>createdBy/createdAt 来自 V410 的授权取证列：谁在什么时候把这家商户加进该账号的范围。
 * 老数据这两列为空时前端显示「—」。
 */
public record OpsMerchantUserDto(
        Long userId,
        /** 脱敏手机号；账号行缺失时为 null（孤儿绑定仍需可见、可回收） */
        String phoneNumber,
        String name,
        String status,
        Long createdBy,
        String createdByName,
        Instant createdAt
) {}
