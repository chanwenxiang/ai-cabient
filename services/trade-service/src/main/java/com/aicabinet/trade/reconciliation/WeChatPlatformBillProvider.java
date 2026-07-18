package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 微信支付 V3 申请交易账单：https://pay.weixin.qq.com/docs/merchant/apis/in-app-payment/bill/download-bill.html
 */
@Component
public class WeChatPlatformBillProvider implements PlatformBillProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatPlatformBillProvider.class);

    private final WeChatPayProperties weChatPayProperties;
    private final ReconciliationProperties reconciliationProperties;
    private final WeChatPayV3Client v3Client;

    public WeChatPlatformBillProvider(WeChatPayProperties weChatPayProperties,
                                        ReconciliationProperties reconciliationProperties,
                                        WeChatPayV3Client v3Client) {
        this.weChatPayProperties = weChatPayProperties;
        this.reconciliationProperties = reconciliationProperties;
        this.v3Client = v3Client;
    }

    @Override
    public String channel() {
        return "WECHAT";
    }

    @Override
    public List<PlatformBillLine> fetchDailyBill(LocalDate date) {
        if (!weChatPayProperties.isConfigured()) {
            throw new IllegalStateException(
                    "WeChat platform bill unavailable: configure WECHAT_* or set RECON_MOCK_ENABLED=true. "
                            + "Empty bill download is disabled to avoid silent MISMATCH.");
        }
        String billType = reconciliationProperties.wechatBillType();
        String path = "/v3/bill/tradebill?bill_date=" + date + "&bill_type=" + billType;
        JsonNode resp = v3Client.get(path);
        String downloadUrl = resp.path("download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalStateException("wechat v3 bill missing download_url: " + resp);
        }
        byte[] data = v3Client.download(downloadUrl);
        String csv = new String(data, StandardCharsets.UTF_8);
        if (csv.trim().startsWith("{")) {
            throw new IllegalStateException("wechat v3 bill download failed: " + csv);
        }
        List<PlatformBillLine> lines = WeChatBillCsvParser.parse(csv, date);
        log.info("wechat v3 bill date={} lines={}", date, lines.size());
        return lines;
    }
}
