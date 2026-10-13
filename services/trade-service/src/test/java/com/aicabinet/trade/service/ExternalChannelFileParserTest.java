package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ExternalImportTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外部渠道文件解析器（CB-036）：模板驱动 + 格式漂移降级 + 人员掩码剥离。 */
class ExternalChannelFileParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExternalChannelFileParser parser = new ExternalChannelFileParser(mapper);

    private ExternalImportTemplate template(String fileType, String sheetStrategy, String qtySemantics,
                                            String keyField, String personSource, String columnMapJson) {
        ExternalImportTemplate t = new ExternalImportTemplate();
        t.setChannelCode("test");
        t.setChannelName("测试渠道");
        t.setFileType(fileType);
        t.setSheetStrategy(sheetStrategy);
        t.setQtySemantics(qtySemantics);
        t.setKeyField(keyField);
        t.setPersonSource(personSource);
        t.setOpType(ExternalImportTemplate.OP_WAREHOUSE_OUT);
        t.setColumnMap(columnMapJson);
        return t;
    }

    // ── CSV GBK（大黄鹅 202606 漂移格式：无补货数量列 → DIRECT 降级 BY_DIFF）──────────────────

    @Test
    void csvGbkByDiffFallbackAndEncoding() throws Exception {
        String csv = """
                商户名称,补货时间,机器编号,商品ID,商品名称,单价,补货前库存,补货后库存
                便利店A,2026/6/2,DH001,P100,冰红茶,3.5,2,10
                便利店A,2026/6/3,DH002,P200,矿泉水,2,5,7
                """;
        byte[] bytes = csv.getBytes(Charset.forName("GB18030"));
        ExternalImportTemplate t = template("CSV_GBK", "FIRST", "DIRECT", "PRODUCT_ID", "MANUAL",
                "{\"extId\":\"商品ID\",\"extName\":\"商品名称\",\"unitPrice\":\"单价\",\"qtyBefore\":\"补货前库存\",\"qtyAfter\":\"补货后库存\",\"happenedAt\":\"补货时间\"}");
        ExternalChannelFileParser.ParseResult r = parser.parse(bytes, "202606.csv", t, "李进学");
        assertEquals(2, r.rows().size());
        ExternalChannelFileParser.ParsedRow first = r.rows().get(0);
        assertEquals("P100", first.extKey());
        assertEquals("冰红茶", first.extName());
        assertEquals(8, first.qty()); // 10 - 2，DIRECT 缺列降级 BY_DIFF
        assertEquals(350L, first.unitPriceCents());
        assertEquals("李进学", first.person()); // MANUAL 覆盖
        assertEquals(java.time.LocalDate.of(2026, 6, 2), first.happenedAt());
        assertTrue(r.errors().isEmpty());
    }

    // ── XLSX 多 sheet（大黄鹅 202609 实证：每线长一个 sheet，SHEET_NAME 归属）─────────────────

    @Test
    void xlsxMultiSheetPersonFromSheetName() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String person : new String[]{"李进学", "黄凯文"}) {
                Sheet sheet = wb.createSheet(person);
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("商品ID");
                header.createCell(1).setCellValue("商品名称");
                header.createCell(2).setCellValue("补货数量");
                Row data = sheet.createRow(1);
                data.createCell(0).setCellValue("P100");
                data.createCell(1).setCellValue("冰红茶");
                data.createCell(2).setCellValue(12);
            }
            wb.write(out);
            bytes = out.toByteArray();
        }
        ExternalImportTemplate t = template("XLSX", "ALL", "DIRECT", "PRODUCT_ID", "SHEET_NAME",
                "{\"extId\":\"商品ID\",\"extName\":\"商品名称\",\"qty\":\"补货数量\"}");
        ExternalChannelFileParser.ParseResult r = parser.parse(bytes, "202609.xlsx", t, null);
        assertEquals(2, r.rows().size());
        assertEquals("李进学", r.rows().get(0).person());
        assertEquals("黄凯文", r.rows().get(1).person());
        assertEquals(12, r.rows().get(0).qty());
    }

    // ── XLS OLE2（乐摇摇实证：老式 .xls，人员列带手机掩码）────────────────────────────────────

    @Test
    void xlsOle2PersonColumnStripsPhoneMask() throws Exception {
        byte[] bytes;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("补货明细");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品名称");
            header.createCell(1).setCellValue("本次补货数量");
            header.createCell(2).setCellValue("补货人员");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("冰红茶");
            data.createCell(1).setCellValue(6);
            data.createCell(2).setCellValue("王仕宏138****5678");
            wb.write(out);
            bytes = out.toByteArray();
        }
        ExternalImportTemplate t = template("XLS", "FIRST", "DIRECT", "NAME", "COLUMN",
                "{\"extName\":\"商品名称\",\"qty\":\"本次补货数量\",\"person\":\"补货人员\"}");
        ExternalChannelFileParser.ParseResult r = parser.parse(bytes, "leyaoyao.xls", t, null);
        assertEquals(1, r.rows().size());
        assertEquals("王仕宏", r.rows().get(0).person());
        assertEquals("冰红茶", r.rows().get(0).extKey()); // NAME 键
    }

    // ── SALES 语义（嗨便利/无人店：销量即调出口径，负数取绝对值）─────────────────────────────

    @Test
    void salesSemanticsAbs() {
        String csv = """
                商品ID,商品名称,实际销售数量
                P100,冰红茶,-5
                """;
        byte[] bytes = csv.getBytes(Charset.forName("GB18030"));
        ExternalImportTemplate t = template("CSV_GBK", "FIRST", "SALES", "PRODUCT_ID", "MANUAL",
                "{\"extId\":\"商品ID\",\"extName\":\"商品名称\",\"qty\":\"实际销售数量\"}");
        ExternalChannelFileParser.ParseResult r = parser.parse(bytes, "hb.csv", t, "张三");
        assertEquals(5, r.rows().get(0).qty());
    }

    // ── 脏行不中断：负数量报错行、无商品信息行跳过、全空 sheet 报错清单 ────────────────────────

    @Test
    void dirtyRowsCollectedAsErrors() {
        String csv = """
                商品名称,补货数量
                冰红茶,3
                坏行,-2
                ,,
                """;
        byte[] bytes = csv.getBytes(Charset.forName("GB18030"));
        ExternalImportTemplate t = template("CSV_GBK", "FIRST", "DIRECT", "NAME", "MANUAL",
                "{\"extName\":\"商品名称\",\"qty\":\"补货数量\"}");
        ExternalChannelFileParser.ParseResult r = parser.parse(bytes, "dirty.csv", t, "张三");
        assertEquals(1, r.rows().size());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("数量为负"));
        assertNull(r.rows().get(0).unitPriceCents());
    }

    @Test
    void garbageFileRejectedWithMessage() {
        ExternalImportTemplate t = template("XLSX", "FIRST", "DIRECT", "NAME", "MANUAL",
                "{\"extName\":\"商品名称\",\"qty\":\"补货数量\"}");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("not excel".getBytes(), "x.xlsx", t, null));
        assertTrue(e.getMessage().contains("文件解析失败"));
    }

    @Test
    void normalizePersonStripsMasksAndBlank() {
        assertEquals("李进学", ExternalChannelFileParser.normalizePerson("李进学138****1234"));
        assertEquals("李进学", ExternalChannelFileParser.normalizePerson(" 李进学 "));
        assertNull(ExternalChannelFileParser.normalizePerson("138****1234"));
        assertNull(ExternalChannelFileParser.normalizePerson(null));
    }

    @Test
    void emptyParseRejected() {
        String csv = ",\n,\n";
        byte[] bytes = csv.getBytes(Charset.forName("GB18030"));
        ExternalImportTemplate t = template("CSV_GBK", "FIRST", "DIRECT", "NAME", "MANUAL",
                "{\"extName\":\"商品名称\",\"qty\":\"补货数量\"}");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> parser.parse(bytes, "empty.csv", t, "张三"));
        assertTrue(e.getMessage().contains("未解析到任何数据行"));
    }
}
