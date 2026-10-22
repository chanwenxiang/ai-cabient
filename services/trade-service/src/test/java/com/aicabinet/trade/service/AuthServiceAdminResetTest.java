package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdminPasswordResetRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.LoginThrottleService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.PhoneVerifyLogMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayOauthClient;
import com.aicabinet.trade.sms.SmsCodeService;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.ServerBootMarker;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import com.aicabinet.trade.wechat.WeChatWebOAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 忘记密码（短信 + 图形验证码）重置的角色/状态闸门。
 * 这两条是「超管忘密码只能走登录页入口」结论的另一半：非运营与已停用账号一律拒，
 * 且必须在消耗短信验证码之前就拒（否则等于给消费者号白发货）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceAdminResetTest {

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private JwtService jwtService;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WeChatWebOAuthClient weChatWebOAuthClient;
    @Mock private AlipayOauthClient alipayOauthClient;
    @Mock private SmsCodeService smsCodeService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ServerBootMarker serverBootMarker;
    @Mock private LoginThrottleService loginThrottleService;
    @Mock private PhoneVerifyLogMapper phoneVerifyLogMapper;
    @Mock private DistributedLockService distributedLockService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userInfoRepository, userAccountRepository, jwtService,
                weChatMiniAppClient, weChatWebOAuthClient, alipayOauthClient, smsCodeService,
                passwordEncoder, serverBootMarker,
                new AuthProperties("test-secret", 3600L, false, false, 5, 15, null,
                        AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS),
                new SecurityProperties(false),
                loginThrottleService, phoneVerifyLogMapper, distributedLockService,
                new UserConsentLogService(mock(UserConsentLogMapper.class)), null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private static UserInfo user(long userId, String phone, String accountType, String status) {
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setPhoneNumber(phone);
        user.setAccountType(accountType);
        user.setStatus(status);
        return user;
    }

    @Test
    void adminResetPassword_whenConsumerAccount_rejectsBeforeConsumingSmsCode() {
        UserInfo consumer = user(1001L, "13800000001", CabinetConstants.ACCOUNT_TYPE_CONSUMER, "ACTIVE");
        when(userInfoRepository.findByPhoneNumber("13800000001")).thenReturn(Optional.of(consumer));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13800000001", "123456", null, null, "Newpass123")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getMessage().contains(ApiMessages.CONSUMER_CANNOT_USE_ADMIN));
        verify(smsCodeService, never()).verifyCode(anyString(), anyString());
    }

    @Test
    void adminResetPassword_whenOperatorDisabled_rejectsBeforeConsumingSmsCode() {
        UserInfo disabled = user(CabinetConstants.OPERATOR_USER_ID_START, "13900000001",
                CabinetConstants.ACCOUNT_TYPE_OPERATOR, "INACTIVE");
        when(userInfoRepository.findByPhoneNumber("13900000001")).thenReturn(Optional.of(disabled));
        when(userInfoRepository.findById(CabinetConstants.OPERATOR_USER_ID_START))
                .thenReturn(Optional.of(disabled));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13900000001", "123456", null, null, "Newpass123")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getMessage().contains(ApiMessages.ACCOUNT_DISABLED));
        verify(smsCodeService, never()).verifyCode(anyString(), anyString());
    }

    @Test
    void adminResetPassword_whenPhoneUnknown_unifiesMessageAndRecordsThrottle() {
        when(userInfoRepository.findByPhoneNumber("13800000009")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13800000009", "123456", null, null, "Newpass123")));

        // 防枚举：未注册与密码错误同一文案——忘密码流程里用户没输过密码，这句看着奇怪但是刻意的
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains(ApiMessages.INVALID_CREDENTIALS));
        verify(loginThrottleService).recordFailure("13800000009");
        verify(smsCodeService, never()).verifyCode(anyString(), anyString());
    }

    @Test
    void adminResetPassword_whenWeakPassword_rejectsBeforeLookingUpUser() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13900000001", "123456", null, null, "12345678")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userInfoRepository, never()).findByPhoneNumber(anyString());
    }

    @Test
    void adminResetPassword_whenCodeInvalid_doesNotTouchLoginThrottle() {
        long userId = CabinetConstants.OPERATOR_USER_ID_START + 3L;
        UserInfo operator = user(userId, "13900000003", CabinetConstants.ACCOUNT_TYPE_OPERATOR, "ACTIVE");
        when(userInfoRepository.findByPhoneNumber("13900000003")).thenReturn(Optional.of(operator));
        when(userInfoRepository.findById(userId)).thenReturn(Optional.of(operator));
        when(smsCodeService.verifyCode("13900000003", "000000")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13900000003", "000000", null, null, "Newpass123")));

        // 短信码错只 400，不写登录节流——否则输错几次验证码就能把运营锁在后台外
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains(ApiMessages.INVALID_CODE));
        verify(loginThrottleService, never()).recordFailure(anyString());
    }

    @Test
    void adminResetPassword_whenCodeValid_storesEncoderOutputNotPlaintext() {
        long userId = CabinetConstants.OPERATOR_USER_ID_START + 7L;
        UserInfo operator = user(userId, "13900000007", CabinetConstants.ACCOUNT_TYPE_OPERATOR, "ACTIVE");
        operator.setPasswordHash("old-bcrypt-hash");
        when(userInfoRepository.findByPhoneNumber("13900000007")).thenReturn(Optional.of(operator));
        when(userInfoRepository.findById(userId)).thenReturn(Optional.of(operator));
        when(smsCodeService.verifyCode("13900000007", "123456")).thenReturn(true);
        when(distributedLockService.tryLock(AccountService.userAccountLockKey(userId), 60L, 5L)).thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(operator));
        when(passwordEncoder.encode("Newpass123")).thenReturn("new-bcrypt-hash");

        service.adminResetPassword(new AdminPasswordResetRequest(
                "13900000007", "123456", null, null, "Newpass123"));

        assertEquals("new-bcrypt-hash", operator.getPasswordHash());
        verify(userInfoRepository).save(operator);
        verify(distributedLockService).unlock(AccountService.userAccountLockKey(userId));
    }
}
