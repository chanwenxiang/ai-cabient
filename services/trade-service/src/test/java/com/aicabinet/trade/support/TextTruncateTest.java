package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A7/P3：substring(0,max) 按 UTF-16 code unit 截断会把代理对（emoji）切碎成
 * 非法孤代理。safeTruncate 须在 code point 边界回退。
 */
class TextTruncateTest {

    private static final String EMOJI = "😀"; // U+1F600，UTF-16 = 高代理 + 低代理（2 code units）

    @Test
    void 短串原样返回() {
        assertEquals("abc", TextTruncate.safeTruncate("abc", 128));
    }

    @Test
    void null返回null() {
        assertNull(TextTruncate.safeTruncate(null, 128));
    }

    @Test
    void 边界不落在代理对内_截到max() {
        String s = "a".repeat(127) + EMOJI + "b"; // 127 ASCII + emoji(2) + 1
        String out = TextTruncate.safeTruncate(s, 128);
        // max=128 落在 emoji 低代理位 → 回退到 127（emoji 整体被舍弃，不产孤代理）
        assertEquals(127, out.length());
        assertEquals("a".repeat(127), out);
    }

    @Test
    void 边界恰在代理对前_整对保留() {
        String s = "a".repeat(126) + EMOJI; // 126 + 2 = 128
        String out = TextTruncate.safeTruncate(s, 128);
        assertEquals(128, out.length());
        assertEquals(s, out);
    }

    @Test
    void 连续emoji_中间边界不碎() {
        String s = EMOJI.repeat(10); // 20 code units
        String out = TextTruncate.safeTruncate(s, 15);
        // 15 落在第 8 个 emoji 的低代理位 → 回退 14 = 7 个完整 emoji
        assertEquals(14, out.length());
        assertEquals(EMOJI.repeat(7), out);
    }

    @Test
    void max小于1_返回空串() {
        assertEquals("", TextTruncate.safeTruncate("abc", 0));
    }
}
