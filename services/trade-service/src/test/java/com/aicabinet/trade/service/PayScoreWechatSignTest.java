package com.aicabinet.trade.service;

import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AgreementChargeClient;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayScoreClient;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信支付分（免确认模式）签约/回调：真实分支 PENDING 语义 + 回调激活（CB-064）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayScoreWechatSignTest {
    static {
        // LambdaQueryWrapper 需 TableInfo 缓存（同 PayScoreAlipayAgreementTest 的 M01 门禁口径）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserInfo.class);
    }

    @Mock UserInfoMapper userInfoRepository;
    @Mock PayScoreProperties payScoreProperties;
    @Mock SecurityProperties securityProperties;
    @Mock WeChatPayProperties weChatPayProperties;
    @Mock AlipayPayClient alipayPayClient;
    @Mock AgreementChargeClient agreementChargeClient;
    @Mock DistributedLockService distributedLockService;
    @Mock WeChatPayScoreClient weChatPayScoreClient;
    @Mock com.aicabinet.trade.mapper.PayScoreOrderMapper payScoreOrderMapper;
    @Mock com.aicabinet.trade.mapper.CabinetOrderLineMapper cabinetOrderLineMapper;

    @InjectMocks PayScoreService payScoreService;

    @BeforeEach
    void stubLock() {
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    private UserInfo userWithWx(long userId, String wxOpenId) {
        UserInfo u = new UserInfo();
        u.setUserId(userId);
        u.setWxOpenId(wxOpenId);
        return u;
    }

    @Test
    void sign_notConfiguredNonMock_fails501() {
        when(payScoreProperties.enabled()).thenReturn(false);
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(weChatPayScoreClient.isContractReady()).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> payScoreService.signWeChatPayScore(10001L));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        verify(weChatPayScoreClient, never()).createPermission(anyString(), anyString());
    }

    @Test
    void sign_realFlow_storesPendingMarkerAndReturnsToken() {
        UserInfo u = userWithWx(10002L, "oX-abc");
        when(payScoreProperties.enabled()).thenReturn(true);
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(weChatPayScoreClient.isContractReady()).thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(10002L)).thenReturn(Optional.of(u));
        when(userInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.fasterxml.jackson.databind.JsonNode resp = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("apply_permissions_token", "TOKEN-1");
        when(weChatPayScoreClient.createPermission(anyString(), eq("oX-abc"))).thenReturn(resp);

        PayScoreService.WechatSignResult result = payScoreService.signWeChatPayScore(10002L);

        assertTrue(result.pending());
        assertEquals("TOKEN-1", result.applyPermissionsToken());
        assertTrue(result.contractId().startsWith(PayScoreService.WECHAT_PENDING_PREFIX));
        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        assertFalse(cap.getValue().isPayscoreEnabled(), "激活前不得放行免密");
        assertEquals(result.contractId(), cap.getValue().getPayscoreContractId());
    }

    @Test
    void sign_realFlow_requiresWxOpenId() {
        UserInfo u = userWithWx(10002L, " ");
        when(payScoreProperties.enabled()).thenReturn(true);
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(weChatPayScoreClient.isContractReady()).thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(10002L)).thenReturn(Optional.of(u));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> payScoreService.signWeChatPayScore(10002L));
        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        verify(weChatPayScoreClient, never()).createPermission(anyString(), anyString());
    }

    @Test
    void notify_activatesPendingUser() {
        UserInfo pending = userWithWx(10002L, "oX-abc");
        pending.setPayscoreEnabled(false);
        pending.setPayscoreContractId(PayScoreService.WECHAT_PENDING_PREFIX + "PSU10002-1");
        when(userInfoRepository.selectList(any())).thenReturn(List.of(pending));
        when(userInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.fasterxml.jackson.databind.JsonNode event = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("out_request_no", "PSU10002-1")
                .put("authorization_code", "AUTH-CODE-9")
                .put("openid", "oX-abc");

        payScoreService.handleWechatPermissionNotify(event);

        ArgumentCaptor<UserInfo> cap = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).save(cap.capture());
        assertTrue(cap.getValue().isPayscoreEnabled());
        assertEquals("AUTH-CODE-9", cap.getValue().getPayscoreContractId());
        assertTrue(payScoreService.isPasswordFreeReadyForChannel(cap.getValue(), "WECHAT"));
    }

    @Test
    void notify_unmatchedEvent_isSwallowedWithoutSave() {
        when(userInfoRepository.selectList(any())).thenReturn(List.of());
        com.fasterxml.jackson.databind.JsonNode event = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("out_request_no", "PSU-NOBODY")
                .put("authorization_code", "AUTH-ORPHAN")
                .put("openid", "oX-nobody");

        payScoreService.handleWechatPermissionNotify(event);

        verify(userInfoRepository, never()).save(any());
    }

    @Test
    void pendingMarker_notReadyForWechatChannel() {
        UserInfo pending = userWithWx(10002L, "oX-abc");
        pending.setPayscoreEnabled(false);
        pending.setPayscoreContractId(PayScoreService.WECHAT_PENDING_PREFIX + "PSU10002-1");
        assertFalse(payScoreService.isPasswordFreeReadyForChannel(pending, "WECHAT"));
    }

    @Test
    void clientContractReady_gatesOnServiceIdAndWechatConfig() {
        when(weChatPayProperties.isConfigured()).thenReturn(false);
        when(payScoreProperties.hasServiceId()).thenReturn(true);
        WeChatPayScoreClient client = new WeChatPayScoreClient(
                new com.aicabinet.trade.payment.WeChatPayV3Client(
                        weChatPayProperties, null, null,
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                weChatPayProperties, payScoreProperties);
        assertFalse(client.isContractReady());
        when(weChatPayProperties.isConfigured()).thenReturn(true);
        assertTrue(client.isContractReady());
    }

    @Test
    void notify_parseGuard_usesSharedTrustChain() {
        // 回调信任链复用 WeChatPayNotifyService（验签/防重放/解密）；此处只钉「未配置即拒绝」口径，
        // 完整验签链路在 WeChatPayNotifyService 自身测试覆盖。
        when(weChatPayProperties.isConfigured()).thenReturn(false);
        WeChatPayNotifyService svc = new WeChatPayNotifyService(
                weChatPayProperties, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.parseAndVerify("{}", "1", "n", "s", "serial"));
        assertEquals(ApiMessages.WECHAT_PAY_NOT_CONFIGURED, ex.getMessage());
        // 防误引：HttpServerErrorException 不应在本链路出现（它是转发侧错误形态）
        assertFalse(HttpServerErrorException.class.isInstance(ex));
    }
}
