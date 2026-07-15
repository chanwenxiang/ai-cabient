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
            log.warn("alipay not configured, skip bill download for {}", date);
            return List.of();
        }
        JsonNode response = openApiClient.execute(
                "alipay.data.dataservice.bill.downloadurl.query",
                Map.of("bill_type", "trade", "bill_date", date.toString())
        );
        String downloadUrl = response.path("bill_download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            log.warn("alipay bill url empty for date={}", date);
            return List.of();
        }
        byte[] data = openApiClient.download(downloadUrl);
        List<PlatformBillLine> lines = AlipayBillCsvParser.parseDownloadedBytes(data, date);
        log.info("alipay bill date={} lines={}", date, lines.size());
        return lines;
    }
}
