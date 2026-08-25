package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 消费者设置结算优先支付方式：BALANCE / WECHAT / ALIPAY。 */
public record SetPayPreferredChannelRequest(
        @NotBlank
        @Pattern(regexp = "(?i)BALANCE|WECHAT|ALIPAY", message = "channel must be BALANCE, WECHAT or ALIPAY")
        String channel
) {}
