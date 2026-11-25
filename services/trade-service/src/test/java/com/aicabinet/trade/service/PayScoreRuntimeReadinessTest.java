package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.AgreementChargeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayScoreRuntimeReadinessTest {

    @Mock private PayScoreProperties payScoreProperties;
    @Mock private SecurityProperties securityProperties;
    @Mock private WeChatPayProperties weChatPayProperties;
    @Mock private AlipayPayClient alipayPayClient;
    @Mock private AgreementChargeClient agreementChargeClient;

    private PayScoreService service() {
        return new PayScoreService(payScoreProperties, securityProperties, weChatPayProperties,
                null, alipayPayClient, agreementChargeClient, null, null, null, null);
    }

    private UserInfo wechatUser() {
        UserInfo user = new UserInfo();
        user.setUserId(1L);
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("PS-CONTRACT");
        return user;
    }

    @Test
    void automaticResolution_fallsBackToBalanceWhenRuntimeDisabled() {
        when(payScoreProperties.liveChargeEnabled()).thenReturn(false);

        assertEquals(PayChannels.BALANCE,
                service().resolveChargeChannel(wechatUser(), PayChannels.WECHAT));
    }

    @Test
    void automaticResolution_usesWechatWhenRuntimeReady() {
        when(payScoreProperties.liveChargeEnabled()).thenReturn(true);
        when(payScoreProperties.enabled()).thenReturn(true);
        when(weChatPayProperties.isConfigured()).thenReturn(true);

        assertEquals(PayChannels.WECHAT,
                service().resolveChargeChannel(wechatUser(), PayChannels.WECHAT));
    }

    @Test
    void explicitCharge_rejectsInsteadOfSilentBalanceFallback() {
        when(payScoreProperties.liveChargeEnabled()).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().chargeExplicit(wechatUser(), "ORDER-1", 100, "test", PayChannels.WECHAT));

        assertEquals(HttpStatus.PRECONDITION_FAILED, error.getStatusCode());
    }
}
