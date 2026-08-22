package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayScoreAlipayAgreementTest {

    @Mock UserInfoMapper userInfoRepository;
    @Mock com.aicabinet.trade.config.PayScoreProperties payScoreProperties;
    @Mock com.aicabinet.trade.config.SecurityProperties securityProperties;
    @Mock com.aicabinet.trade.config.WeChatPayProperties weChatPayProperties;
    @Mock com.aicabinet.trade.payment.AlipayPayClient alipayPayClient;
    @Mock com.aicabinet.trade.payment.AgreementChargeClient agreementChargeClient;

    @InjectMocks PayScoreService payScoreService;

    @Test
    void pendingPrefix_notReady() {
        UserInfo u = new UserInfo();
        u.setAlipayAgreementId("PENDING:EXT-1");
        assertFalse(PayScoreService.isActiveAlipayAgreementId(u.getAlipayAgreementId()));
        assertFalse(payScoreService.isPasswordFreeReady(u));
    }

    @Test
    void bindFromNotify_activatesAgreement() {
        UserInfo u = new UserInfo();
        u.setUserId(10001L);
        u.setAlipayAgreementId("PENDING:EXT-ABC");
        when(userInfoRepository.findByAlipayAgreementId("PENDING:EXT-ABC")).thenReturn(Optional.of(u));
        when(userInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean ok = payScoreService.bindAlipayAgreementFromNotify("EXT-ABC", "2024AGREE001", "NORMAL");
        assertTrue(ok);
        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        assertEquals("2024AGREE001", cap.getValue().getAlipayAgreementId());
        assertTrue(payScoreService.isPasswordFreeReady(cap.getValue()));
    }
}
