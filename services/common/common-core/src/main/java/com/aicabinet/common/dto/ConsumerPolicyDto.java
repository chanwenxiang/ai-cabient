package com.aicabinet.common.dto;

import java.util.List;

/** 消费者条款（用户协议 / 隐私 / 退款 / 账单）。 */
public record ConsumerPolicyDto(
        String type, String title, String version, String updatedAt, List<ConsumerPolicySectionDto> sections) {}
