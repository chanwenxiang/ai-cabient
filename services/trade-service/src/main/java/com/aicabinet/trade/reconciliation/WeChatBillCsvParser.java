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
                            platformTradeNo, merchantOrderNo, amountCents, tradeTime, "WECHAT",
                            parseFeeCents(cols), line
                    ));
                } catch (Exception e) {
                    log.debug("skip wechat bill line: {}", line);
                }
            }
        }
        return result;
    }

    /**
     * CB-020③：解析「手续费」列——微信官方交易账单 ALL 型 27 列的 0 基第 22 列。
     * <p>独立 try：手续费解析失败<b>只置 null 不丢行</b>——金额对账是主目标，
     * 不能因费用列异常把整行从对账里剔除（否则该单变成 PLATFORM_ONLY 假差异）。
     * 退款行手续费列为 0.00，同样走 {@code yuanToCents}。
     */
    private static Long parseFeeCents(String[] cols) {
        if (cols.length < 23 || cols[22].isBlank()) {
            return null;
        }
        try {
            return yuanToCents(cols[22].trim());
        } catch (Exception e) {
            log.debug("skip wechat bill fee: {}", cols[22]);
            return null;
        }
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
