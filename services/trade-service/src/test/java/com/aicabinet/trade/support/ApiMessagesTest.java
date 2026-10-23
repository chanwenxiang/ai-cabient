package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMessagesTest {

    @Test
    void formatValidationFieldError_usesChineseLabels() {
        assertEquals("手机号不能为空", ApiMessages.formatValidationFieldError("phoneNumber", "不能为空"));
        assertEquals("密码不能为空", ApiMessages.formatValidationFieldError("password", "不能为空"));
    }

    @Test
    void formatValidationFieldError_keepsExplicitChineseMessage() {
        assertEquals("手机号不能为空", ApiMessages.formatValidationFieldError("phoneNumber", "手机号不能为空"));
        assertEquals("密码错误", ApiMessages.translate(ApiMessages.INVALID_CREDENTIALS));
    }

    @Test
    void formatValidationFieldError_translatesEnglishRangeMessages() {
        // 2026-10-23 实证：@Min(100) 英文 locale 默认消息曾被兜底误报为「amountCents不能为空」
        assertEquals(
                "amountCents不能低于100",
                ApiMessages.formatValidationFieldError("amountCents", "must be greater than or equal to 100"));
        assertEquals(
                "amountCents不能高于500000",
                ApiMessages.formatValidationFieldError("amountCents", "must be less than or equal to 500000"));
    }
}
