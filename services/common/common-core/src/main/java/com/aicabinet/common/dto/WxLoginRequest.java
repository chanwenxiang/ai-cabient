package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 微信小程序登录请求。
 * <p>C13：phoneNumber 仅作 dev mock 兼容；生产绑定手机号必须传 phoneCode
 * （小程序 getPhoneNumber 授权码，由服务端换取真实手机号）。</p>
 */
public record WxLoginRequest(
        @NotBlank String code,
        String phoneNumber,
        String phoneCode
) {}
