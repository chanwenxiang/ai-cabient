package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.util.YuanFen;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WeChatFundBillCsvParser {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WeChatFundBillCsvParser() {}

    public static FundBill parse(String csv, LocalDate billDate) {
        List<FundFlowLine> lines = new ArrayList<>();
        Long endingBalance = null;
        long net = 0;
        boolean summary = false;
        for (String raw : csv.split("\\R")) {
            String row = raw.strip();
            if (row.startsWith("`")) {
                row = row.substring(1);
            }
            if (row.startsWith("资金流水总笔数")) {
                summary = true;
                continue;
            }
            if (summary || row.isEmpty()) {
                continue;
            }
            List<String> cols = parseRow(row);
            if (cols.isEmpty() || cols.get(0).equals("记账时间")) {
                continue;
            }
            if (cols.size() < 11) {
                throw new IllegalArgumentException("微信资金账单列数不足：" + cols.size());
            }
            long amount = YuanFen.toCents(new BigDecimal(cols.get(6)));
            long balance = YuanFen.toCents(new BigDecimal(cols.get(7)));
            String direction = cols.get(5);
            if (!"收入".equals(direction) && !"支出".equals(direction)) {
                throw new IllegalArgumentException("微信资金账单收支类型非法：" + direction);
            }
            net += "收入".equals(direction) ? amount : -amount;
            endingBalance = balance;
            lines.add(new FundFlowLine(
                    Instant.from(LocalDateTime.parse(cols.get(0), TIME).atZone(ZONE)),
                    cols.get(1), cols.get(2), cols.get(3), cols.get(4), direction,
                    amount, balance, cols.get(8), cols.get(9), cols.get(10)));
        }
        return new FundBill(billDate, List.copyOf(lines), endingBalance, net);
    }

    private static List<String> parseRow(String row) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(clean(value.toString()));
                value.setLength(0);
            } else {
                value.append(ch);
            }
        }
        values.add(clean(value.toString()));
        return values;
    }

    private static String clean(String value) {
        String text = value.strip();
        if (text.startsWith("`")) {
            text = text.substring(1);
        }
        return text;
    }

    public record FundBill(LocalDate date, List<FundFlowLine> lines, Long endingBalanceCents, long netCents) {}

    public record FundFlowLine(
            Instant bookTime,
            String wxTradeNo,
            String flowNo,
            String bizName,
            String bizType,
            String direction,
            long amountCents,
            long balanceCents,
            String applicant,
            String remark,
            String voucherNo
    ) {}
}
