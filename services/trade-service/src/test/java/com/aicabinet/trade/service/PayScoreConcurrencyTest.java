package com.aicabinet.trade.service;

import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.AgreementChargeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayScoreConcurrencyTest {

    @Mock private PayScoreProperties payScoreProperties;
    @Mock private SecurityProperties securityProperties;
    @Mock private WeChatPayProperties weChatPayProperties;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private AlipayPayClient alipayPayClient;
    @Mock private AgreementChargeClient agreementChargeClient;
    @Mock private DistributedLockService distributedLockService;

    private PayScoreService service;

    @BeforeEach
    void setUp() {
        service = new PayScoreService(payScoreProperties, securityProperties, weChatPayProperties,
                userInfoRepository, alipayPayClient, agreementChargeClient, distributedLockService);
    }

    @Test
    void signWeChatPayScore_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PayScoreService.payScoreUserLockKey(900L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.signWeChatPayScore(900L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void signAlipayAgreement_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PayScoreService.payScoreUserLockKey(901L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.signAlipayAgreement(901L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
