package com.aicabinet.trade.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 身份证号格式校验（18 位，GB 11643-1999 校验位）。
 *
 * <p>🔴 <b>为什么在调 vendor 之前本地做校验位</b>：二要素核验按<b>次计费</b>，
 * 「格式都不对」的请求打给 vendor 是白烧钱（部分 vendor 对非法号码也计费）。
 * 本地 mod-11 校验位先行拦截，400 直接回给用户改。
 *
 * <p>⚠️ 本类只做<b>格式与校验位</b>，不判断「号码是否真实存在」——
 * 那是 vendor 权威库的职责。校验位通过 ≠ 号码有效。
 */
public final class IdCardValidator {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /** mod 11 余数 → 校验字符（余 2 为 X）。 */
    private static final char[] CHECK_MAP = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCardValidator() {}

    /**
     * 校验 18 位身份证号格式 + 校验位。
     *
     * @throws ResponseStatusException 400 格式错误（消息不含用户输入，防 PII 进日志/响应回显）
     */
    public static void requireValid(String idCard) {
        if (idCard == null || !idCard.matches("\\d{17}[\\dXx]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "身份证号格式不正确");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCard.charAt(i) - '0') * WEIGHTS[i];
        }
        char expected = CHECK_MAP[sum % 11];
        char actual = Character.toUpperCase(idCard.charAt(17));
        if (expected != actual) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "身份证号校验位不正确，请核对后重试");
        }
    }
}
