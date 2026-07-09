package com.aicabinet.common.dto;

import java.util.List;

public record OpsUserMerchantsDto(
        Long userId,
        List<String> merchantIds
) {}
