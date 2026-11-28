package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class WeChatPayV3ClientRefundQueryTest {

    @Test
    void queryRefundOmitsUnsupportedMchidParameter() {
        ObjectMapper mapper = new ObjectMapper();
        WeChatPayV3Client client = spy(new WeChatPayV3Client(
                mock(WeChatPayProperties.class),
                mock(WeChatPayV3Signer.class),
                mock(WeChatPlatformCertificateStore.class),
                mapper));
        JsonNode response = mapper.createObjectNode().put("status", "SUCCESS");
        doReturn(response).when(client).get(anyString());

        assertSame(response, client.queryRefund("OUT-TRADE", "BR3-S1"));

        verify(client).get("/v3/refund/domestic/refunds/BR3-S1");
    }
}
