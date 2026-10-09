package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CB-021：后台设密统一规则。负向验证——注入真实违规值确认校验会红，
 * 而不是只测合规值能过（铁律 #4：改校验后必须负向验证）。
 */
class PasswordPolicyTest {

    @Test
    void validPassword_passes() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("abc123"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("pass12"));
        // 恰好 6 位与恰好 64 位都合法
        assertDoesNotThrow(() -> PasswordPolicy.validate("a1b2c3"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("a".repeat(63) + "1"));
    }

    @Test
    void digitsOnly_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PasswordPolicy.validate("123456"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("新密码须同时包含字母和数字", ex.getReason());
    }

    @Test
    void lettersOnly_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PasswordPolicy.validate("abcdef"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("新密码须同时包含字母和数字", ex.getReason());
    }

    @Test
    void tooShort_rejectedWithLengthMessage() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PasswordPolicy.validate("a1b2c"));
        assertEquals("新密码长度需在 6-64 位之间", ex.getReason());
    }

    @Test
    void tooLong_rejectedWithLengthMessage() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PasswordPolicy.validate("a".repeat(63) + "12"));
        assertEquals("新密码长度需在 6-64 位之间", ex.getReason());
    }

    @Test
    void null_rejectedWithLengthMessage() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> PasswordPolicy.validate(null));
        assertEquals("新密码长度需在 6-64 位之间", ex.getReason());
    }
}
