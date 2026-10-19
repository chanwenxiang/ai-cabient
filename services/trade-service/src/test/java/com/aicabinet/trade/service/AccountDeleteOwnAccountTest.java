package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserConsentLog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.identity.IdentityVerifyClient;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-149（2026-10-19 拍板）：消费者自助注销账户——守卫 fail-closed + 匿名化 + 同意留痕。
 * CB-059：网信办 2026-09 通报「仅人工办理且响应迟缓」算无效注销 ⇒ 服务端自助闭环为硬要求。
 * 持久化走 LambdaUpdateWrapper 显式 set（M01：updateById 忽略 null 列，匿名化清列必须显式），
 * 故 happy path 断言落在 wrapper 的 sqlSet 段而不是实体字段。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountDeleteOwnAccountTest {

    static {
        // 纯 Mockito 环境需手动注册 TableInfo 缓存（lambda 列解析依赖，惯例同 RevenueSplitServiceTest）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, UserInfo.class);
    }

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private PayScoreService payScoreService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private IdentityVerifyClient identityVerifyClient;
    @Mock private BalanceRefundRequestMapper balanceRefundRequestMapper;
    @Mock private UserConsentLogMapper consentLogMapper;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(userInfoRepository, userAccountRepository,
                payScoreService, balanceLedgerService, distributedLockService, identityVerifyClient,
                balanceRefundRequestMapper, new UserConsentLogService(consentLogMapper), null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private UserInfo activeConsumer(long userId) {
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setPhoneNumber("13800001234");
        user.setName("张三");
        user.setEmail("a@b.c");
        user.setNickname("昵称");
        user.setWxOpenId("openid-x");
        user.setPasswordHash("hash");
        user.setStatus("ACTIVE");
        return user;
    }

    private UserAccount zeroAccount(long userId) {
        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalanceCents(0);
        account.setFrozenCents(0);
        return account;
    }

    @Test
    void deleteOwnAccount_happyPath_anonymizesAndLogsConsent() {
        UserInfo user = activeConsumer(100L);
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(zeroAccount(100L)));
        when(balanceRefundRequestMapper.countByUserIdAndStatus(100L, "PROCESSING")).thenReturn(0L);
        when(userInfoRepository.update(any(), any())).thenReturn(1);

        service.deleteOwnAccount(100L);

        // 匿名化断言落在 wrapper 的 sqlSet（M01 显式 set(null) 才能真正清列）
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<UserInfo>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(userInfoRepository).update(isNull(), captor.capture());
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserInfo>) captor.getValue())
                .getSqlSet();
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("phone_number"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("name"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("email"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("wx_open_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("password_hash"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("totp_secret"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("payscore_contract_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("alipay_agreement_id"));
        org.junit.jupiter.api.Assertions.assertTrue(sqlSet.contains("status"));
        // DC-6 同源教训：值在参数表不在 sqlSet——DELETED 必须从 paramNameValuePairs 里断言
        org.junit.jupiter.api.Assertions.assertTrue(((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserInfo>) captor.getValue())
                .getParamNameValuePairs().containsValue("DELETED"));
        // 同意留痕：ACCOUNT_DELETE 落 user_consent_log
        verify(consentLogMapper).insertIgnoreConflict(eq(100L), eq(UserConsentLog.TYPE_ACCOUNT_DELETE), anyString());
    }

    @Test
    void deleteOwnAccount_operatorForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount((long) CabinetConstants.OPERATOR_USER_ID_START));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(userInfoRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void deleteOwnAccount_balanceNonZero_conflict() {
        UserInfo user = activeConsumer(100L);
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        UserAccount account = zeroAccount(100L);
        account.setBalanceCents(500);
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(100L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userInfoRepository, never()).update(any(), any());
    }

    @Test
    void deleteOwnAccount_frozenCents_conflict() {
        UserInfo user = activeConsumer(100L);
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        UserAccount account = zeroAccount(100L);
        account.setFrozenCents(1);
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(100L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteOwnAccount_payscoreActive_conflict() {
        UserInfo user = activeConsumer(100L);
        user.setPayscoreEnabled(true);
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(zeroAccount(100L)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(100L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteOwnAccount_alipayAgreementActive_conflict() {
        UserInfo user = activeConsumer(100L);
        user.setAlipayAgreementId("AGMT-1");
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(zeroAccount(100L)));
        // isActiveAlipayAgreementId 是静态判定（非 mock）：AGMT-1 非待生效前缀 ⇒ 视为已生效

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(100L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteOwnAccount_processingRefund_conflict() {
        UserInfo user = activeConsumer(100L);
        when(userInfoRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(user));
        when(userAccountRepository.findById(100L)).thenReturn(Optional.of(zeroAccount(100L)));
        when(balanceRefundRequestMapper.countByUserIdAndStatus(100L, "PROCESSING")).thenReturn(1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(100L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userInfoRepository, never()).update(any(), any());
    }

    @Test
    void deleteOwnAccount_missingUser_notFound() {
        when(userInfoRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteOwnAccount(404L));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
