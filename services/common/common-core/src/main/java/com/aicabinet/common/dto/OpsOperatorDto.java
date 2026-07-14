package com.aicabinet.common.dto;

import java.util.List;

public record OpsOperatorDto(
        Long userId,
        String phoneNumber,
        String name,
        List<String> roleNames,
        List<Long> roleIds
) {}
