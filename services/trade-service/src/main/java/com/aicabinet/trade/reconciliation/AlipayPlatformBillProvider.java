package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.payment.AlipayOpenApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 支付宝账单：alipay.data.dataservice.bill.downloadurl.query → 下载 CSV/ZIP → 解析明细。
 */
@Component
public class AlipayPlatformBillProvider implements PlatformBillProvider {

    private static final Logger log = LoggerFactory.getLogger(AlipayPlatformBillProvider.class);

    private final AlipayProperties alipayProperties;
    private final AlipayOpenApiClient openApiClient;

    public AlipayPlatformBillProvider(AlipayProperties alipayProperties,
                                      AlipayOpenApiClient openApiClient) {
        this.alipayProperties = alipayProperties;
        this.openApiClient = openApiClient;
    }

    @Override
    public String channel() {
        return "ALIPAY";
    }

    @Override
    public List<PlatformBillLine> fetchDailyBill(LocalDate date) {
        if (!alipayProperties.isConfigured()) {
            throw new IllegalStateException(
                    "Alipay platform bill unavailable: configure ALIPAY_* or set RECON_MOCK_ENABLED=true. "
                            + "Empty bill download is disabled to avoid silent MISMATCH.");
        }
        JsonNode response = openApiClient.execute(
                "alipay.data.dataservice.bill.downloadurl.query",
                Map.of("bill_type", "trade", "bill_date", date.toString())
        );
        String downloadUrl = response.path("bill_download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            // R22-8（四端审计 2026-11-12）：fail-fast 对齐微信侧（WeChatPlatformBillProvider
            // 同位置抛 IllegalStateException）。此前 log.warn + 空列表静默返回——账单文件
            // 未出/不可得时，当日无流水会被记 MATCHED 假绿（「空对空算通过」）、有流水则
            // 伪装成巨大差异，两者都比显性失败更危险。
            throw new IllegalStateException(
                    "alipay bill download_url missing for date=" + date + "（账单未出或接口未返回下载地址）"
                            + " — empty bill download is disabled to avoid silent MATCHED");
        }
        byte[] data = openApiClient.download(downloadUrl);
        List<PlatformBillLine> lines = AlipayBillCsvParser.parseDownloadedBytes(data, date);
        log.info("alipay bill date={} lines={}", date, lines.size());
        return lines;
    }
}
