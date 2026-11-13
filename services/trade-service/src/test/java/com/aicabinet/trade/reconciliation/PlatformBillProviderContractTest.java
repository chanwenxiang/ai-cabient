package com.aicabinet.trade.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.payment.AlipayOpenApiClient;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S3-3c：渠道账单 Provider 契约测试——WECHAT / ALIPAY 两个实现跑同一失败矩阵。
 *
 * 契约（R22-8 拍板口径，两侧必须一致）：
 *   ① 未配置 ⇒ IllegalStateException（fail-closed），**禁止返回空列表**——空账单=「空对空
 *      MATCHED 假绿」或「伪装成巨大差异」，都比显性失败更危险；
 *   ② 配置了但 download_url 缺失 ⇒ IllegalStateException（R22-8：支付宝侧曾 log.warn+
 *      空列表静默返回，已改 fail-fast 与微信对齐）；
 *   ③ channel() 唯一且不混用。
 *
 * 成功路径的 CSV 解析形态差异属各 parser 单测（WeChatBillCsvParserTest /
 * AlipayBillCsvParserTest），不在契约范围。
 */
class PlatformBillProviderContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final ReconciliationProperties RECON =
            new ReconciliationProperties(false, "ALL", false, "");

    // ── 契约①：未配置必须 fail-closed ──

    @Test
    @DisplayName("契约① WECHAT：未配置 ⇒ IllegalStateException，绝不静默返回空")
    void wechatNotConfigured_throws() {
        WeChatPayProperties unconfigured =
                new WeChatPayProperties(false, null, null, null, null, null, null, null, false);
        WeChatPlatformBillProvider provider =
                new WeChatPlatformBillProvider(unconfigured, RECON, mock(WeChatPayV3Client.class));
        assertThrows(IllegalStateException.class, () -> provider.fetchDailyBill(DAY));
    }

    @Test
    @DisplayName("契约① ALIPAY：未配置 ⇒ IllegalStateException，绝不静默返回空")
    void alipayNotConfigured_throws() {
        AlipayProperties unconfigured =
                new AlipayProperties(false, null, null, null, null, null, null, null, null, null, null);
        AlipayPlatformBillProvider provider =
                new AlipayPlatformBillProvider(unconfigured, mock(AlipayOpenApiClient.class));
        assertThrows(IllegalStateException.class, () -> provider.fetchDailyBill(DAY));
    }

    // ── 契约②：download_url 缺失必须 fail-closed（R22-8 两侧对齐）──

    @Test
    @DisplayName("契约② WECHAT：download_url 缺失 ⇒ IllegalStateException")
    void wechatMissingDownloadUrl_throws() throws Exception {
        WeChatPayV3Client client = mock(WeChatPayV3Client.class);
        when(client.get(anyString())).thenReturn(MAPPER.readTree("{}"));
        WeChatPlatformBillProvider provider =
                new WeChatPlatformBillProvider(configuredWeChat(), RECON, client);
        assertThrows(IllegalStateException.class, () -> provider.fetchDailyBill(DAY));
    }

    @Test
    @DisplayName("契约② ALIPAY：bill_download_url 缺失 ⇒ IllegalStateException")
    void alipayMissingDownloadUrl_throws() throws Exception {
        AlipayOpenApiClient client = mock(AlipayOpenApiClient.class);
        when(client.execute(anyString(), any())).thenReturn(MAPPER.readTree("{}"));
        AlipayPlatformBillProvider provider =
                new AlipayPlatformBillProvider(configuredAlipay(), client);
        assertThrows(IllegalStateException.class, () -> provider.fetchDailyBill(DAY));
    }

    // ── 契约③：channel 唯一且不混用 ──

    @Test
    @DisplayName("契约③：channel() 唯一且不混用")
    void channels_areDistinct() {
        assertEquals("WECHAT", new WeChatPlatformBillProvider(
                configuredWeChat(), RECON, mock(WeChatPayV3Client.class)).channel());
        assertEquals("ALIPAY", new AlipayPlatformBillProvider(
                configuredAlipay(), mock(AlipayOpenApiClient.class)).channel());
    }

    private static WeChatPayProperties configuredWeChat() {
        return new WeChatPayProperties(
                true, "APP", "MCH", "https://notify.example/v3", "V3KEY", "SERIAL",
                "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----", "", true);
    }

    private static AlipayProperties configuredAlipay() {
        return new AlipayProperties(
                true, "APP", "PID", "KEY", "PUBKEY", "https://gateway.example",
                "https://notify.example", "https://return.example", "", "", "");
    }
}
