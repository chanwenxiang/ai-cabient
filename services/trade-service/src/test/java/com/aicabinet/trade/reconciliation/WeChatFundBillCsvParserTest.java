package com.aicabinet.trade.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeChatFundBillCsvParserTest {

    @Test
    void parsesOperatingAccountFlowAndEndingBalance() {
        String csv = """
                记账时间,微信支付业务单号,资金流水单号,业务名称,业务类型,收支类型,收支金额(元),账户结余(元),资金变更提交申请人,备注,业务凭证号
                `2026-11-23 16:58:08,`179542428227169901,`4500000564202611235700992607,`充值/提现,`扫码充值,`收入,`2.00,`2.00,`1573719961,`测试代码,`RCHG17954242811433108
                资金流水总笔数,收入笔数,收入金额,支出笔数,支出金额
                `1,`1,`2.00,`0,`0.00
                """;

        WeChatFundBillCsvParser.FundBill bill = WeChatFundBillCsvParser.parse(csv, LocalDate.of(2026, 11, 23));

        assertEquals(1, bill.lines().size());
        assertEquals(200L, bill.netCents());
        assertEquals(200L, bill.endingBalanceCents());
        assertEquals("收入", bill.lines().get(0).direction());
        assertEquals("RCHG17954242811433108", bill.lines().get(0).voucherNo());
    }

    @Test
    void emptyBillHasNoEndingBalance() {
        WeChatFundBillCsvParser.FundBill bill = WeChatFundBillCsvParser.parse("", LocalDate.of(2026, 11, 23));

        assertEquals(0, bill.lines().size());
        assertEquals(0L, bill.netCents());
        assertNull(bill.endingBalanceCents());
    }
}
