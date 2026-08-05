package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 支付宝网页授权登录：authCode 换 user_id；mock 模式下可用 mock 标识。 */
public record AlipayLoginRequest(
        @NotBlank String authCode
) {}
