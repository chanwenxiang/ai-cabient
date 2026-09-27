package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S3：CSV 导出统一转义——公式注入中和 + 分隔符/引号/换行包裹。 */
class CsvCellsTest {

    @Test
    void formulaPrefixes_getNeutralized() {
        assertEquals("'=SUM(A1)", CsvCells.escape("=SUM(A1)"));
        assertEquals("'+8613800000000", CsvCells.escape("+8613800000000"));
        assertEquals("'-1;DROP TABLE users", CsvCells.escape("-1;DROP TABLE users"));
        assertEquals("'@cmd", CsvCells.escape("@cmd"));
        assertEquals("'\ttabbed", CsvCells.escape("\ttabbed"));
        // 先中和（前缀 '）再因含 \r 触发包裹
        assertEquals("\"'\rcarriage\"", CsvCells.escape("\rcarriage"));
    }

    @Test
    void separatorsAndQuotes_getWrapped() {
        assertEquals("\"a,b\"", CsvCells.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvCells.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvCells.escape("line1\nline2"));
    }

    @Test
    void plainText_andNulls_unchanged() {
        assertEquals("前海易购", CsvCells.escape("前海易购"));
        assertEquals("1790508321710280131581", CsvCells.escape("1790508321710280131581"));
        assertEquals("", CsvCells.escape(null));
        assertEquals("", CsvCells.escape("null"));
        assertEquals("", CsvCells.escape(""));
    }
}
