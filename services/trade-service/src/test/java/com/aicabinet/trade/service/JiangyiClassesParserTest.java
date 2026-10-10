package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * classes.txt 解析器（CB-023）：classId↔textName 对照表的唯一生成入口，
 * 行数≠quantity 必须拒绝（错位映射一旦激活直接错误扣款——负向用例钉死）。
 */
class JiangyiClassesParserTest {

    @Test
    void base0FirstLineClassIdIsZero() {
        var parsed = JiangyiClassesParser.parse("农夫山泉 550ml\n可口可乐 330ml", 2,
                JiangyiClassesParser.CLASS_ID_BASE_ZERO);
        assertEquals(0, parsed.rows().get(0).classId());
        assertEquals(1, parsed.rows().get(1).classId());
        assertEquals("农夫山泉 550ml", parsed.rows().get(0).textName());
        assertEquals(0, parsed.rows().get(0).lineNumber());
    }

    @Test
    void base1FirstLineClassIdIsOne() {
        var parsed = JiangyiClassesParser.parse("农夫山泉 550ml\n可口可乐 330ml", 2,
                JiangyiClassesParser.CLASS_ID_BASE_ONE);
        assertEquals(1, parsed.rows().get(0).classId());
        assertEquals(2, parsed.rows().get(1).classId());
    }

    @Test
    void crlfAndBlankLinesAreSkippedWithoutShiftingClassIds() {
        var parsed = JiangyiClassesParser.parse("商品A\r\n\r\n商品B\n  \n商品C\r\n", 3,
                JiangyiClassesParser.CLASS_ID_BASE_ZERO);
        assertEquals(List.of("商品A", "商品B", "商品C"),
                parsed.rows().stream().map(r -> r.textName()).toList());
        assertEquals(List.of(0, 1, 2),
                parsed.rows().stream().map(r -> r.classId()).toList());
    }

    @Test
    void quantityMismatchIsRejectedWithDiffSummary() {
        var e = assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> JiangyiClassesParser.parse("商品A\n商品B", 5, 0));
        assertTrue(e.getMessage().contains("2"), "消息含实际行数：" + e.getMessage());
        assertTrue(e.getMessage().contains("5"), "消息含 quantity：" + e.getMessage());
        assertTrue(e.getMessage().contains("商品A") && e.getMessage().contains("商品B"),
                "消息含前几行摘要：" + e.getMessage());
    }

    @Test
    void quantityZeroMeansNoRowCountCheck() {
        var parsed = JiangyiClassesParser.parse("商品A\n商品B", 0, 0);
        assertEquals(2, parsed.rows().size());
    }

    @Test
    void invalidClassIdBaseIsRejected() {
        assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> JiangyiClassesParser.parse("商品A", 1, 2));
    }

    @Test
    void blankContentIsRejected() {
        assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> JiangyiClassesParser.parse("  \n ", 1, 0));
        assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> JiangyiClassesParser.parse(null, 1, 0));
    }

    @Test
    void classesVersionIsTwelveHexCharsDerivedFromContent() {
        var parsed = JiangyiClassesParser.parse("商品A\n商品B", 2, 0);
        assertTrue(Pattern.matches("[0-9a-f]{12}", parsed.classesVersion()),
                "sha256 前 12 位 hex：" + parsed.classesVersion());
        // 内容变 → 版本变（下发审计指纹有效性）
        var other = JiangyiClassesParser.parse("商品A\n商品C", 2, 0);
        assertNotEquals(parsed.classesVersion(), other.classesVersion());
        // 相同内容 → 相同版本（幂等）
        var again = JiangyiClassesParser.parse("商品A\n商品B", 2, 0);
        assertEquals(parsed.classesVersion(), again.classesVersion());
    }
}
