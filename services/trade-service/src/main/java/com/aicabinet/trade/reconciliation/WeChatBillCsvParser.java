package com.aicabinet.trade.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WeChatBillCsvParser {

    private static final Logger log = LoggerFactory.getLogger(WeChatBillCsvParser.class);
    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private WeChatBillCsvParser() {}

    public static List<PlatformBillLine> parse(String csv, LocalDate billDate) {
        String[] lines = csv.split("\n");
        List<PlatformBillLine> result = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("总交易单数")) {
                break;
            }
            String[] cols = line.replace("`", "").split(",");
            if (cols.length >= 13) {
                Instant tradeTime = billDate.atStartOfDay(ZONE).toInstant();
                try {
                    String merchantOrderNo = cols[6].trim();
                    String platformTradeNo = cols[5].trim();
                    long amountCents = yuanToCents(cols[12].trim());
                    result.add(new PlatformBillLine(
                            platformTradeNo, merchantOrderNo, amountCents, tradeTime, "WECHAT", line
                    ));
                } catch (Exception e) {
                    log.debug("skip wechat bill line: {}", line);
                }
            }
        }
        return result;
    }

    public static LocalDate parseBillDateParam(String yyyyMMdd) {
        return LocalDate.parse(yyyyMMdd, BILL_DATE);
    }

    private static long yuanToCents(String yuan) {
        return new BigDecimal(yuan.trim())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
