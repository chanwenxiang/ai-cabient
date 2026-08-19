package com.aicabinet.trade.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WeChatBillCsvParser {

    private static final Logger log = LoggerFactory.getLogger(WeChatBillCsvParser.class);
    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private WeChatBillCsvParser() {}

    public static List<PlatformBillLine> parse(String csv, LocalDate billDate) {
        String[] lines = csv.split("\n");
        List<PlatformBillLine> result = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("总交易单数")) {
                break;
            }
            String[] cols = line.replace("`", "").split(",");
            if (cols.length < 13) {
                continue;
            }
            Instant tradeTime = billDate.atStartOfDay(zone).toInstant();
            try {
                String merchantOrderNo = cols[6].trim();
                String platformTradeNo = cols[5].trim();
                long amountCents = Math.round(Double.parseDouble(cols[12].trim()) * 100);
                result.add(new PlatformBillLine(
                        platformTradeNo, merchantOrderNo, amountCents, tradeTime, "WECHAT", line
                ));
            } catch (Exception e) {
                log.debug("skip wechat bill line: {}", line);
            }
        }
        return result;
    }

    public static LocalDate parseBillDateParam(String yyyyMMdd) {
        return LocalDate.parse(yyyyMMdd, BILL_DATE);
    }
}
