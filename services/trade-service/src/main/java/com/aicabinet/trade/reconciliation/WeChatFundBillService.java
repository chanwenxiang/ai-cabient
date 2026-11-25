package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Component
public class WeChatFundBillService {

    private static final Logger log = LoggerFactory.getLogger(WeChatFundBillService.class);

    private final WeChatPayV3Client client;

    public WeChatFundBillService(WeChatPayV3Client client) {
        this.client = client;
    }

    public WeChatFundBillCsvParser.FundBill fetchOperatingAccount(LocalDate date) {
        String path = "/v3/bill/fundflowbill?bill_date=" + date + "&account_type=OPERATION";
        WeChatPayV3Client.HttpResult result = client.getWithStatus(path);
        JsonNode response = result.body();
        if (result.status() == 404 || "NO_STATEMENT_EXIST".equals(response.path("code").asText(""))) {
            log.info("wechat operating-account fund bill unavailable date={} status={} code={}",
                    date, result.status(), response.path("code").asText(""));
            return WeChatFundBillCsvParser.parse("", date);
        }
        if (!result.is2xx()) {
            throw new IllegalStateException("微信运营账户资金账单查询失败：status=" + result.status()
                    + " code=" + response.path("code").asText("")
                    + " message=" + response.path("message").asText(""));
        }
        String downloadUrl = response.path("download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            log.info("wechat operating-account fund bill unavailable date={} (no download_url)", date);
            return WeChatFundBillCsvParser.parse("", date);
        }
        byte[] data = client.download(downloadUrl,
                response.path("hash_type").asText(null), response.path("hash_value").asText(null));
        String csv = new String(data, StandardCharsets.UTF_8);
        if (csv.trim().startsWith("{")) {
            throw new IllegalStateException("微信运营账户资金账单下载失败：" + csv);
        }
        WeChatFundBillCsvParser.FundBill bill = WeChatFundBillCsvParser.parse(csv, date);
        log.info("wechat operating-account fund bill date={} lines={} netCents={} endingBalanceCents={}",
                date, bill.lines().size(), bill.netCents(), bill.endingBalanceCents());
        return bill;
    }
}
