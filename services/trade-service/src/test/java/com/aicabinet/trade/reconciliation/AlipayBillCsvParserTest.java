package com.aicabinet.trade.reconciliation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AlipayBillCsvParserTest {

    @Test
    void parseCsv_extractsAlipayTradeLines() {
        String csv = """
                #支付宝业务明细查询
                #类型：业务明细
                支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,对方账户,订单金额（元）,商家实收（元）
                2024011522001234567890,ORD-ALIPAY-1,交易,汽水,2024-01-15 10:00:00,2024-01-15 10:00:01,,,,,,3.50,3.50
                """;

        List<PlatformBillLine> lines = AlipayBillCsvParser.parseCsv(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals("ORD-ALIPAY-1", lines.get(0).merchantOrderNo());
        assertEquals(350, lines.get(0).amountCents());
        assertEquals("ALIPAY", lines.get(0).tradeType());
    }

    @Test
    void parseZip_readsCsvFromArchive() throws Exception {
        String csv = """
                支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,对方账户,订单金额（元）,商家实收（元）
                202401152200999,ORD-ZIP-1,交易,苹果,2024-01-15 11:00:00,2024-01-15 11:00:02,,,,,,5.00,5.00
                """;
        byte[] zip = zipSingleCsv("bill.csv", csv.getBytes(StandardCharsets.UTF_8));

        List<PlatformBillLine> lines = AlipayBillCsvParser.parseDownloadedBytes(zip, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals("ORD-ZIP-1", lines.get(0).merchantOrderNo());
        assertEquals(500, lines.get(0).amountCents());
    }

    private static byte[] zipSingleCsv(String name, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
