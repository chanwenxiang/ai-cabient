package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.util.YuanFen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
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
        int skipped = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("总交易单数")) {
                break;
            }
            String[] cols = line.replace("`", "").split(",");
            if (cols.length >= 13) {
                Instant tradeTime = billDate.atStartOfDay(ZONE).toInstant();
                long amountCents;
                try {
                    // 金额列是主对账依据：YuanFen 拒 scale>2，解析失败不得降级金额（会静默错账）。
                    amountCents = YuanFen.toCents(new BigDecimal(cols[12].trim()));
                } catch (RuntimeException e) {
                    // 🔴 不得静默丢行：该单会变成 PLATFORM_ONLY 假差异，与 parseFeeCents
                    //    「费用列异常只置 null 不丢行」是同一原则。保留跳过语义，但必须 WARN + 计数可见。
                    skipped++;
                    log.warn(
                            "skip wechat bill line (amount parse failed): billDate={}, lineNo={}, amountCol={}, reason={}, line={}",
                            billDate, i + 1, cols[12], e.toString(), line);
                    continue;
                }
                try {
                    String merchantOrderNo = cols[6].trim();
                    String platformTradeNo = cols[5].trim();
                    result.add(new PlatformBillLine(
                            platformTradeNo, merchantOrderNo, amountCents, tradeTime, "WECHAT",
                            parseFeeCents(cols), line
                    ));
                } catch (RuntimeException e) {
                    // 非金额异常同样不得静默：旧式 catch(Exception) + log.debug 把整行吞掉且生产不输出
                    skipped++;
                    log.warn(
                            "skip wechat bill line (unexpected): billDate={}, lineNo={}, reason={}, line={}",
                            billDate, i + 1, e.toString(), line);
                }
            } else {
                // 短行原先连 debug 都没有，属完全无痕丢弃
                skipped++;
                log.warn("skip wechat bill line (short columns): billDate={}, lineNo={}, cols={}, line={}",
                        billDate, i + 1, cols.length, line);
            }
        }
        if (skipped > 0) {
            // 汇总：逐行 WARN 之外给出「本次对账少了几行」的总数，运营能直接发现缺口
            log.warn("wechat bill parse finished with skipped rows: billDate={}, kept={}, skipped={}",
                    billDate, result.size(), skipped);
        }
        return result;
    }

    /**
     * CB-020③：解析「手续费」列——微信官方交易账单 ALL 型 27 列的 0 基第 22 列。
     * <p>独立 try：手续费解析失败<b>只置 null 不丢行</b>——金额对账是主目标，
     * 不能因费用列异常把整行从对账里剔除（否则该单变成 PLATFORM_ONLY 假差异）。
     * 退款行手续费列为 0.00，同样走 {@code YuanFen.toCents}。
     */
    private static Long parseFeeCents(String[] cols) {
        if (cols.length < 23 || cols[22].isBlank()) {
            return null;
        }
        try {
            return YuanFen.toCents(new BigDecimal(cols[22].trim()));
        } catch (Exception e) {
            log.debug("skip wechat bill fee: {}", cols[22]);
            return null;
        }
    }

    public static LocalDate parseBillDateParam(String yyyyMMdd) {
        return LocalDate.parse(yyyyMMdd, BILL_DATE);
    }
}
