package com.aicabinet.trade.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeChatBillCsvParserTest {

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
}
