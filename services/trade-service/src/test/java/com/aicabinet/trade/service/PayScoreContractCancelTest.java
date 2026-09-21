package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G9：用户主动解约（{@link PayScoreService#cancelPasswordFree}）。
 *
 * <p>判据设计说明 —— 本测试刻意把「负向」写在最显眼处：
 * <ul>
 *   <li>幂等分支必须**不写库**（只用返回值断言会漏掉"顺手清了一把"的实现）；</li>
 *   <li>非 mock 分支必须**零副作用**：既报 501，又不得落任何写操作 —— 否则就是
 *       「接口说失败、库里已解约」这种最难排查的不一致（本地已解约、渠道仍可扣款）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayScoreContractCancelTest {

    static {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（与 PayScoreAlipayAgreementTest 同因）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserInfo.class);
    }

    @Mock UserInfoMapper userInfoRepository;
    @Mock com.aicabinet.trade.config.PayScoreProperties payScoreProperties;
    @Mock com.aicabinet.trade.config.SecurityProperties securityProperties;
    @Mock com.aicabinet.trade.config.WeChatPayProperties weChatPayProperties;
    @Mock com.aicabinet.trade.payment.AlipayPayClient alipayPayClient;
    @Mock com.aicabinet.trade.payment.AgreementChargeClient agreementChargeClient;
    @Mock DistributedLockService distributedLockService;

    @InjectMocks PayScoreService payScoreService;

    @BeforeEach
    void stubLock() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(userInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private UserInfo signedBoth() {
        UserInfo u = new UserInfo();
        u.setUserId(10001L);
        u.setPayscoreEnabled(true);
        u.setPayscoreContractId("PSC-10001-ABCD1234");
        u.setAlipayAgreementId("ALI-AG-10001-EFGH5678");
        u.setPayPreferredChannel(PayChannels.WECHAT);
        return u;
    }

    @Test
    void cancel_clearsBothContracts_andFallsBackPreferenceToBalance() {
        UserInfo u = signedBoth();
        when(userInfoRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(u));

        var result = payScoreService.cancelPasswordFree(10001L);

        assertTrue(result.terminated());
        assertEquals(2, result.channels().size());
        assertTrue(result.channels().contains(PayChannels.WECHAT));
        assertTrue(result.channels().contains(PayChannels.ALIPAY));

        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        UserInfo saved = cap.getValue();
        assertFalse(saved.isPayscoreEnabled());
        assertNull(saved.getPayscoreContractId());
        assertNull(saved.getAlipayAgreementId());
        assertEquals(PayChannels.BALANCE, saved.getPayPreferredChannel(),
                "偏好指向被解约的渠道必须回落余额，否则留下悬空偏好");
        assertFalse(payScoreService.isPasswordFreeReady(saved));

        // M01：updateById 忽略 null 列 ⇒ 必须再用 wrapper 显式把两个协议号列置 null
        verify(userInfoRepository).update(any(), any());
    }

    @Test
    void cancel_isIdempotent_whenNothingSigned_andWritesNothing() {
        UserInfo u = new UserInfo();
        u.setUserId(10002L);
        u.setPayPreferredChannel(PayChannels.BALANCE);
        when(userInfoRepository.findByIdForUpdate(10002L)).thenReturn(Optional.of(u));

        var result = payScoreService.cancelPasswordFree(10002L);

        assertFalse(result.terminated());
        assertTrue(result.channels().isEmpty());
        verify(userInfoRepository, never()).save(any());
        verify(userInfoRepository, never()).update(any(), any());
    }

    @Test
    void cancel_failsClosed_whenNotMock_andTouchesNothing() {
        UserInfo u = signedBoth();
        when(userInfoRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(u));
        when(securityProperties.mockEnabled()).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> payScoreService.cancelPasswordFree(10001L));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode(),
                "渠道侧解约未接入时必须 501，而不是本地清列造成假解约");
        // 关键负向：失败路径不得有任何**写**副作用（读 user 是允许的）
        verify(userInfoRepository, never()).save(any());
        verify(userInfoRepository, never()).update(any(), any());
    }

    @Test
    void cancel_clearsPendingAlipayAgreement_too() {
        UserInfo u = new UserInfo();
        u.setUserId(10003L);
        u.setAlipayAgreementId("PENDING:EXT-ABC123");
        u.setPayPreferredChannel(PayChannels.ALIPAY);
        when(userInfoRepository.findByIdForUpdate(10003L)).thenReturn(Optional.of(u));

        var result = payScoreService.cancelPasswordFree(10003L);

        assertTrue(result.terminated());
        assertEquals(java.util.List.of(PayChannels.ALIPAY), result.channels());

        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        assertNull(cap.getValue().getAlipayAgreementId());
        assertEquals(PayChannels.BALANCE, cap.getValue().getPayPreferredChannel());
    }

    @Test
    void cancel_keepsPreference_whenItDoesNotPointToCancelledChannel() {
        UserInfo u = new UserInfo();
        u.setUserId(10004L);
        u.setPayscoreEnabled(true);
        u.setPayscoreContractId("PSC-10004-ZZZZ0001");
        // 偏好是余额：解约微信免密不应改动偏好
        u.setPayPreferredChannel(PayChannels.BALANCE);
        when(userInfoRepository.findByIdForUpdate(10004L)).thenReturn(Optional.of(u));

        var result = payScoreService.cancelPasswordFree(10004L);

        assertEquals(java.util.List.of(PayChannels.WECHAT), result.channels());
        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        assertEquals(PayChannels.BALANCE, cap.getValue().getPayPreferredChannel());
    }
}
