package com.aicabinet.common.dto;

import java.util.List;

public record MerchantSubscribeRequest(
        List<String> alertTypes
) {}
