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
}
