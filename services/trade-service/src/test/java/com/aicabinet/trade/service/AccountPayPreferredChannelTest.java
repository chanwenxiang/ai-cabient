package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AccountDto;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.identity.IdentityVerifyClient;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「设为优先支付方式」的就绪闸（F6 顺带收口的一处**真实不一致**）。
 *
 * <p>病根：原先只判「协议号非空」，于是支付宝**签约中**（协议号带 {@code PENDING:} 前缀）也能被写成偏好渠道；
 * 而前端据以禁用选项的 {@code AccountDto.alipayAgreementEnabled} 用的是严格判据
 * （{@code PayScoreService.isActiveAlipayAgreementId}）⇒ 用户看到选项置灰、偏好里却存着它。
 * 现在两处共用 {@link PayScoreService#isChannelUsable}，本测试锁住「同源」。
 *
 * <p>共同前提：**写不成偏好**必须是 412 + **零持久化**（不能出现「报错但已落库」）。
 */
@ExtendWith(MockitoExtension.class)
class AccountPayPreferredChannelTest {

    private static final long USER_ID = 10001L;

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private PayScoreService payScoreService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private IdentityVerifyClient identityVerifyClient;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(userInfoRepository, userAccountRepository,
                payScoreService, balanceLedgerService, distributedLockService, identityVerifyClient, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    // ---------- 余额永远可设 ----------

    @Test
    void balanceIsAlwaysAccepted() {
        UserInfo user = user();
        stubLockAndUser(user);

        AccountDto dto = service.setPayPreferredChannel(USER_ID, "BALANCE");

        assertEquals("BALANCE", user.getPayPreferredChannel());
        verify(userInfoRepository).save(user);
        assertEquals("BALANCE", dto.payPreferredChannel());
    }

    // ---------- 微信：需已开通且协议号非空 ----------

    @Test
    void wechatWithoutContract_rejects412AndPersistsNothing() {
        UserInfo user = user();
        stubLockAndUser(user);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setPayPreferredChannel(USER_ID, "WECHAT"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        verify(userInfoRepository, never()).save(any(UserInfo.class));
    }

    @Test
    void wechatWithContract_isPersisted() {
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("CONTRACT-1");
        stubLockAndUser(user);

        service.setPayPreferredChannel(USER_ID, "wechat");

        // 入参大小写由服务端归一化
        assertEquals("WECHAT", user.getPayPreferredChannel());
        verify(userInfoRepository).save(user);
    }

    // ---------- 支付宝：这里就是那处不一致的守卫 ----------

    /**
     * 🔴 回归守卫：**签约中**（{@code PENDING:} 前缀）不得被设为优先。
     * 修复前此用例会失败（旧判据只看「协议号非空」⇒ 放行）。
     */
    @Test
    void alipayPendingAgreement_rejects412AndPersistsNothing() {
        UserInfo user = user();
        user.setAlipayAgreementId(PayScoreService.ALIPAY_PENDING_PREFIX + "tmp-agreement");
        stubLockAndUser(user);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setPayPreferredChannel(USER_ID, "ALIPAY"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        verify(userInfoRepository, never()).save(any(UserInfo.class));
    }

    @Test
    void alipayActiveAgreement_isPersisted() {
        UserInfo user = user();
        user.setAlipayAgreementId("2026agreement-1");
        stubLockAndUser(user);

        service.setPayPreferredChannel(USER_ID, "ALIPAY");

        assertEquals("ALIPAY", user.getPayPreferredChannel());
        verify(userInfoRepository).save(user);
    }

    /** 不得串台：微信签约不能顶替支付宝签约（反之亦然）。 */
    @Test
    void wechatContractDoesNotAuthoriseAlipayPreference() {
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("CONTRACT-1");
        stubLockAndUser(user);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setPayPreferredChannel(USER_ID, "ALIPAY"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        verify(userInfoRepository, never()).save(any(UserInfo.class));
    }

    // ---------- 入参校验 ----------

    @Test
    void unknownChannel_rejects400() {
        UserInfo user = user();
        stubLockAndUser(user);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setPayPreferredChannel(USER_ID, "PAYPAL"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userInfoRepository, never()).save(any(UserInfo.class));
    }

    // ---------- fixtures ----------

    /**
     * 锁 ＋ 待更新的用户行。成功路径还会经 {@code self.getAccount()} 再读一次用户与账户行，
     * 故一并 stub（失败路径走不到那里，不会产生多余 stub ⇒ 兼容严格 stub 检查）。
     */
    private void stubLockAndUser(UserInfo user) {
        when(distributedLockService.tryLock(AccountService.userAccountLockKey(USER_ID), 60L, 5L))
                .thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        org.mockito.Mockito.lenient().when(userInfoRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));
        org.mockito.Mockito.lenient().when(userAccountRepository.findById(USER_ID))
                .thenReturn(Optional.of(account()));
    }

    private UserInfo user() {
        UserInfo user = new UserInfo();
        user.setUserId(USER_ID);
        return user;
    }

    private UserAccount account() {
        UserAccount account = new UserAccount();
        account.setUserId(USER_ID);
        account.setBalanceCents(9000);
        return account;
    }
}
