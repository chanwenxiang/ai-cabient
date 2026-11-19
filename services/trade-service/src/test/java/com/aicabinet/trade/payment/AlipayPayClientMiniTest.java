package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayMiniProperties;
import com.aicabinet.trade.config.AlipayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 支付宝**小程序**支付（alipay.trade.create）的参数契约。
 *
 * <p>为什么值得单独钉：这条链路没有真实凭证就调不通，一旦参数写错（漏 buyer_id、
 * 产品码写成 QUICK_WAP_WAY、金额单位错），只会在生产真机上被支付宝以
 * 「交易类型不符 / 参数错误」拒绝，而本地单测是唯一能提前发现的地方。
 */
class AlipayPayClientMiniTest {

    private static final String MP_APP_ID = "2021007118617439";
    private static final String BUYER_ID = "2088000000000001";

    private AlipayProperties alipayProperties() {
        return new AlipayProperties(
                true, "2021007112617393", "", "app-private-key", "alipay-public-key",
                "https://openapi.alipay.com/gateway.do", "", "", "", "", "");
    }

    private AlipayPayClient client(AlipayMiniProperties mini, AlipayOpenApiClient openApi) {
        return new AlipayPayClient(alipayProperties(), mini, openApi);
    }

    @Test
    void createMiniPay_sendsJsapiProductCodeBuyerIdAndYuanAmount() throws Exception {
        AlipayOpenApiClient openApi = mock(AlipayOpenApiClient.class);
        when(openApi.executeAs(eq(MP_APP_ID), eq("alipay.trade.create"), anyMap()))
                .thenReturn(new ObjectMapper().readTree("{\"code\":\"10000\",\"trade_no\":\"2026111922001\"}"));
        AlipayPayClient client = client(new AlipayMiniProperties(true, MP_APP_ID), openApi);

        AlipayPayClient.AlipayPrepayResult result =
                client.createMiniPay("R20261119001", 1999, "AI开门柜充值", BUYER_ID);

        assertEquals("2026111922001", result.tradeNo());
        // 小程序内支付没有回跳页：payUrl / payFormHtml 都应为空（客户端只认 tradeNo）
        assertEquals(null, result.payUrl());
        assertEquals(null, result.payFormHtml());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(openApi).executeAs(eq(MP_APP_ID), eq("alipay.trade.create"), captor.capture());
        Map<String, Object> biz = captor.getValue();
        assertEquals("R20261119001", biz.get("out_trade_no"));
        assertEquals("19.99", biz.get("total_amount"), "金额必须按元、两位小数传，传分会被放大 100 倍");
        assertEquals(BUYER_ID, biz.get("buyer_id"), "trade.create 必须带 buyer_id，否则支付宝直接拒绝");
        assertEquals("JSAPI_PAY", biz.get("product_code"), "小程序支付产品码不能是 QUICK_WAP_WAY");
    }

    @Test
    void createMiniPay_failsClosedWhenMiniAppIdMissing() {
        AlipayOpenApiClient openApi = mock(AlipayOpenApiClient.class);
        AlipayPayClient client = client(new AlipayMiniProperties(false, ""), openApi);

        assertThrows(IllegalStateException.class,
                () -> client.createMiniPay("R1", 100, "充值", BUYER_ID));
        verifyNoInteractions(openApi);
    }

    @Test
    void createMiniPay_failsClosedWithoutBuyerId() {
        AlipayOpenApiClient openApi = mock(AlipayOpenApiClient.class);
        AlipayPayClient client = client(new AlipayMiniProperties(true, MP_APP_ID), openApi);

        assertThrows(IllegalStateException.class,
                () -> client.createMiniPay("R1", 100, "充值", "  "));
        verifyNoInteractions(openApi);
    }

    @Test
    void createMiniPay_rejectsResponseWithoutTradeNo() throws Exception {
        AlipayOpenApiClient openApi = mock(AlipayOpenApiClient.class);
        when(openApi.executeAs(any(), any(), anyMap()))
                .thenReturn(new ObjectMapper().readTree("{\"code\":\"10000\"}"));
        AlipayPayClient client = client(new AlipayMiniProperties(true, MP_APP_ID), openApi);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.createMiniPay("R1", 100, "充值", BUYER_ID));
        assertTrue(ex.getMessage().contains("trade_no"), "缺 trade_no 时必须点名，否则前端只看到空白收银台");
    }
}
