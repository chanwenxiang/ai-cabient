package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdminPasswordResetRequest;
import com.aicabinet.common.dto.AlipayLoginRequest;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.LoginThrottleService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.PhoneVerifyLogMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayOauthClient;
import com.aicabinet.trade.sms.SmsCodeService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthConcurrencyTest {

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
                new AuthProperties("test-secret", 3600L, false, false, 5, 15, null),
                loginThrottleService, phoneVerifyLogMapper, distributedLockService);
    }

    @Test
    void wxLogin_whenLockBusy_rejectsWithConflict() {
        when(weChatMiniAppClient.code2Session("wx-code"))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult("openid-1", "sess"));
        when(distributedLockService.tryLock(
                eq(AuthService.wxOpenIdLockKey("openid-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.wxLogin(new WxLoginRequest("wx-code", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void alipayLogin_whenLockBusy_rejectsWithConflict() {
        when(alipayOauthClient.resolveUserId("ali-code")).thenReturn("alipay-user-1");
        when(distributedLockService.tryLock(
                eq(AuthService.alipayUserLockKey("alipay-user-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.alipayLogin(new AlipayLoginRequest("ali-code")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void adminResetPassword_whenLockBusy_rejectsWithConflict() {
        UserInfo operator = new UserInfo();
        operator.setUserId(CabinetConstants.OPERATOR_USER_ID_START);
        operator.setPhoneNumber("13900000001");
        operator.setStatus("ACTIVE");
        when(userInfoRepository.findByPhoneNumber("13900000001")).thenReturn(Optional.of(operator));
        when(userInfoRepository.findById(CabinetConstants.OPERATOR_USER_ID_START)).thenReturn(Optional.of(operator));
        when(smsCodeService.verifyCode("13900000001", "123456")).thenReturn(true);
        when(distributedLockService.tryLock(
                eq(AccountService.userAccountLockKey(CabinetConstants.OPERATOR_USER_ID_START)),
                eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adminResetPassword(new AdminPasswordResetRequest(
                        "13900000001", "123456", null, null, "newpass1")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
