package com.aicabinet.trade.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 身份证格式/校验位本地拦截（2026-10-20，二要素改完整证件号配套）。
 *
 * <p>🔴 存在理由：vendor 按次计费，格式错误的请求不该发出去。
 */
class IdCardValidatorTest {

    @Test
    @DisplayName("合法号码（校验位 X）⇒ 通过")
    void validWithX() {
        // 11010519491231002X：mod-11 校验位计算为 X（GB 11643-1999）
        assertDoesNotThrow(() -> IdCardValidator.requireValid("11010519491231002X"));
        // 小写 x 归一接受
        assertDoesNotThrow(() -> IdCardValidator.requireValid("11010519491231002x"));
    }

    @Test
    @DisplayName("合法号码（校验位数字）⇒ 通过")
    void validWithDigit() {
        assertDoesNotThrow(() -> IdCardValidator.requireValid("110105194912310011"));
    }

    @Test
    @DisplayName("🔴 校验位错误 ⇒ 400（会白烧 vendor 一次调用的典型脏数据）")
    void invalidChecksum() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> IdCardValidator.requireValid("110105194912310022"));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("🔴 长度/字符集错误 ⇒ 400（含旧契约的 4 位残号）")
    void invalidFormat() {
        assertThrows(ResponseStatusException.class, () -> IdCardValidator.requireValid("1234"));
        assertThrows(ResponseStatusException.class, () -> IdCardValidator.requireValid("1101051949123100"));
        assertThrows(ResponseStatusException.class, () -> IdCardValidator.requireValid(null));
        assertThrows(ResponseStatusException.class, () -> IdCardValidator.requireValid("11010519491231002A"));
    }
}
