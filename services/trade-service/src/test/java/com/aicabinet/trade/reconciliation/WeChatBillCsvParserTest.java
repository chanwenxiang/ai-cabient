package com.aicabinet.trade.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeChatBillCsvParserTest {

    /** 微信官方交易账单（ALL 型）27 列表头——列序以官方文档为准，手续费=0 基第 22 列（CB-020③）。 */
    private static final String OFFICIAL_HEADER =
            "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,"
                    + "付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,"
                    + "退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注";

    @Test
    void parse_weChatCsv_extractsMerchantOrderAndAmount() {
        String[] cols = new String[25];
        Arrays.fill(cols, "");
        cols[5] = "4200001234";
        cols[6] = "ORD-001";
        cols[12] = "3.50";
        String csv = "交易时间,微信订单号,商户订单号,...\n" + String.join(",", cols);

        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals("ORD-001", lines.get(0).merchantOrderNo());
        assertEquals("4200001234", lines.get(0).platformTradeNo());
        assertEquals(350, lines.get(0).amountCents());
    }

    @Test
    void parse_official27Cols_extractsFeeFromColumn22() {
        // 官方列序真实样例（字段前带反引号，parser 需剥除）：手续费 ¥1.20 = 120 分
        String csv = OFFICIAL_HEADER + "\n"
                + "`2024-01-15 10:00:00,`wxappid,`1900000001,`1900000002,`"
                + ",`4200001234,`ORD-001,`oX-abc,`JSAPI,`SUCCESS,`ICBC_CREDIT,`CNY,`3.50,`0.00"
                + ",`0,`0,`0.00,`0.00,`,`,`测试商品,`{},`1.20,`0.60%,`3.50,`0.00,`";

        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals("ORD-001", lines.get(0).merchantOrderNo());
        assertEquals(350, lines.get(0).amountCents());
        assertEquals(120L, lines.get(0).feeCents());
    }

    @Test
    void parse_official27Cols_refundLineKeepsZeroFee() {
        // 退款行：手续费列明确 0.00 → fee=0（非 null——账单提供了数据，与「未提供」语义不同）
        String csv = OFFICIAL_HEADER + "\n"
                + "`2024-01-15 11:00:00,`wxappid,`1900000001,`1900000002,`"
                + ",`4200005678,`ORD-REFUND,`oX-abc,`JSAPI,`REFUND,`ICBC_CREDIT,`CNY,`0.00,`0.00"
                + ",`4200005678-REF,`ORD-REFUND,`2.00,`0.00,`FULL,`SUCCESS,`测试商品,`{},`0.00,`0.60%,`2.00,`2.00,`";

        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals(0L, lines.get(0).feeCents());
    }

    @Test
    void parse_shortLineWithoutFeeColumn_feeIsNull() {
        // 22 列（无手续费列）⇒ fee=null 而非 0——上层据此走估算兜底
        String[] cols = new String[22];
        Arrays.fill(cols, "");
        cols[5] = "4200009999";
        cols[6] = "ORD-SHORT";
        cols[12] = "1.00";
        String csv = OFFICIAL_HEADER + "\n" + String.join(",", cols);

        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals(100, lines.get(0).amountCents());
        assertNull(lines.get(0).feeCents());
    }

    @Test
    void parse_summaryLineStopsParsing_evenWithFeeColumn() {
        // 汇总行「总交易单数」必须在数据行后终止解析，且其「手续费总金额」不进逐行集合
        String[] cols = new String[27];
        Arrays.fill(cols, "");
        cols[5] = "4200001234";
        cols[6] = "ORD-001";
        cols[12] = "3.50";
        cols[22] = "1.20";
        String csv = OFFICIAL_HEADER + "\n" + String.join(",", cols)
                + "\n总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额"
                + "\n`1,`3.50,`0.00,`0.00,`1.20,`3.50,`0.00";

        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, LocalDate.of(2024, 1, 15));

        assertEquals(1, lines.size());
        assertEquals(120L, lines.get(0).feeCents());
    }
}
