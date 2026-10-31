package com.aicabinet.common.dto;

import java.util.List;

/** G2-11 ⑦：商户 → 已授权运营账号清单。 */
public record OpsMerchantUsersDto(
        String merchantId,
        String merchantName,
        List<OpsMerchantUserDto> users
) {}
