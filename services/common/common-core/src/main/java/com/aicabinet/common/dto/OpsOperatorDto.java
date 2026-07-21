package com.aicabinet.common.dto;

import java.util.List;

public record OpsOperatorDto(
        Long userId,
        String phoneNumber,
        String name,
        String status,
        List<String> roleNames,
        List<Long> roleIds,
        /** 绑定商户；空列表表示全局数据范围（未限定设备） */
        List<String> merchantIds,
        List<String> merchantNames
) {}
