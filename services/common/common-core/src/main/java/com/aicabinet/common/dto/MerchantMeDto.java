package com.aicabinet.common.dto;

import java.util.List;

public record MerchantMeDto(
        Long userId,
        String phoneNumber,
        String displayName,
        List<MerchantDto> merchants,
        List<String> permissions,
        boolean canEditPricing,
        /** 绑定商户功能包并集：field / biz / team */
        List<String> enabledPacks
) {}
