package com.aicabinet.common.dto;

import java.util.List;

/** 运营双因子认证绑定信息（secret 与后备码仅此一次明文返回）。 */
public record TwoFactorEnrollDto(
        String secret,
        String otpauthUri,
        List<String> recoveryCodes
) {}
