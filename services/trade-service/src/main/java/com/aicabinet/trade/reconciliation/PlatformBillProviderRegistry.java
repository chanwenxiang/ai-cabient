package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class PlatformBillProviderRegistry {

    private final ReconciliationProperties properties;
    private final MockPlatformBillProvider mockProvider;
    private final WeChatPlatformBillProvider weChatProvider;
    private final AlipayPlatformBillProvider alipayProvider;

    public PlatformBillProviderRegistry(ReconciliationProperties properties,
                                        MockPlatformBillProvider mockProvider,
                                        WeChatPlatformBillProvider weChatProvider,
                                        AlipayPlatformBillProvider alipayProvider) {
        this.properties = properties;
        this.mockProvider = mockProvider;
        this.weChatProvider = weChatProvider;
        this.alipayProvider = alipayProvider;
    }

    public List<PlatformBillLine> fetchBill(String channel, LocalDate date) {
        String ch = channel != null ? channel.toUpperCase() : "WECHAT";
        if (properties.mockEnabled()) {
            return mockProvider.fetchDailyBill(date);
        }
        return switch (ch) {
            case "WECHAT" -> weChatProvider.fetchDailyBill(date);
            case "ALIPAY" -> alipayProvider.fetchDailyBill(date);
            default -> throw new IllegalArgumentException(ApiMessages.UNSUPPORTED_CHANNEL + "：" + channel);
        };
    }
}
