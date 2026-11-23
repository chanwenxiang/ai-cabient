package com.aicabinet.trade.support;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * 邀请码生成（营销 2）。
 *
 * <p>字符集 32 个：去掉 0/O/1/I 这类手输必错的字符——邀请码是要被念出来、抄下来的。
 * 8 位 ⇒ 32^8 ≈ 1.1e12 组合，配合 {@code member.invite_code} 的 partial unique 索引足够。
 *
 * <p>这里只负责「生成 + 去重探测」，不碰数据库：{@code taken} 由调用方注入，
 * 这样建号路径（{@code MemberService}）与存量补码路径（{@code MemberInviteService}）能用同一份逻辑，
 * 不会出现两套字符集/两套长度。
 */
public final class InviteCodes {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 8;
    /** 冲突重试上限：唯一索引兜底，这里只是避免偶发碰撞直接报错。 */
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    /**
     * @param taken 判重函数（查库）；传 {@code null} 表示不判重
     * @throws IllegalStateException 连续 {@value #MAX_ATTEMPTS} 次都撞码（几乎只会在判重函数恒真时发生）
     */
    public static String next(Predicate<String> taken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = random();
            if (taken == null || !taken.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("邀请码生成连续冲突 " + MAX_ATTEMPTS + " 次");
    }

    static String random() {
        char[] buf = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    /** 归一化：去空白 + 转大写（用户手输小写不该被拒）。 */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
