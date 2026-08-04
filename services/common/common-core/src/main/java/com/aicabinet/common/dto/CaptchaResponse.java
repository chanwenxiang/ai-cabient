package com.aicabinet.common.dto;

/**
 * 图形验证码：captchaId 用于校验；imageBase64 为 PNG data URL（含 data:image/png;base64, 前缀）。
 */
public record CaptchaResponse(String captchaId, String imageBase64) {}
