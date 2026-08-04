package com.aicabinet.common.dto;

import java.util.List;

public record MerchantNotifyPrefDto(
        boolean wxBound,
        List<String> enabledAlertTypes
) {}
