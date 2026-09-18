package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.LoginThrottleService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.PhoneVerifyLogMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C13 / H22 / H23：微信登录手机号必须服务端换取；短信验证码登录接入 2FA；刷新会话校验停用账号。
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePhoneAndTwoFactorTest {

    private static final String OPEN_ID = "openid-1";
    private static final String PHONE = "13800138000";

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
                new SecurityProperties(false),
                loginThrottleService, phoneVerifyLogMapper, distributedLockService, null);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private UserInfo consumerUser() {
        UserInfo user = new UserInfo();
        user.setUserId(100L);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_CONSUMER);
        user.setPhoneNumber(PHONE);
        user.setStatus("ACTIVE");
        return user;
    }

    private void stubSuccessfulLoginOrCreate(UserInfo user) {
        when(distributedLockService.tryLock(AuthService.wxOpenIdLockKey(OPEN_ID), 60L, 5L)).thenReturn(true);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.empty());
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(userInfoRepository.findByIdForUpdate(user.getUserId())).thenReturn(Optional.of(user));
        when(jwtService.createToken(user.getUserId(), CabinetConstants.ACCOUNT_TYPE_CONSUMER)).thenReturn("token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
    }

    @Test
    void wxLogin_withPhoneCode_bindsServerResolvedPhoneNumber() {
        // C13：phoneCode 非空时走服务端 getuserphonenumber，绑定的是换来的手机号
        UserInfo user = consumerUser();
        stubSuccessfulLoginOrCreate(user);
        when(weChatMiniAppClient.code2Session("wx-code"))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult(OPEN_ID, "sess"));
        when(weChatMiniAppClient.getPhoneNumber("phone-code")).thenReturn(PHONE);

        LoginResponse response = service.wxLogin(new WxLoginRequest("wx-code", null, "phone-code"));

        assertEquals(user.getUserId(), response.userId());
        verify(userInfoRepository).save(user);
        assertEquals(OPEN_ID, user.getWxOpenId());
    }

    @Test
    void wxLogin_clientPhoneWithoutCode_rejectedWhenNotMock() {
        // C13：mock 关闭时，客户端明文 phoneNumber 无授权码 → 拒绝（防知手机号即可接管账号）
        when(weChatMiniAppClient.code2Session("wx-code"))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult(OPEN_ID, "sess"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.wxLogin(new WxLoginRequest("wx-code", PHONE, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("需提供手机号授权码", ex.getReason());
        verify(weChatMiniAppClient, never()).getPhoneNumber(anyString());
    }

    @Test
    void wxLogin_clientPhoneWithoutCode_allowedWhenMock() {
        // dev 兼容：mock 开启时允许沿用请求体 phoneNumber，且不触发服务端换号
        UserInfo user = consumerUser();
        stubSuccessfulLoginOrCreate(user);
        when(weChatMiniAppClient.code2Session("wx-code"))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult(OPEN_ID, "sess"));
        ReflectionTestUtils.setField(service, "securityProperties", new SecurityProperties(true));

        LoginResponse response = service.wxLogin(new WxLoginRequest("wx-code", PHONE, null));

        assertEquals(user.getUserId(), response.userId());
        verify(weChatMiniAppClient, never()).getPhoneNumber(anyString());
    }

    @Test
    void wxLogin_phoneCodeResolvedEmpty_rejected() {
        when(weChatMiniAppClient.code2Session("wx-code"))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult(OPEN_ID, "sess"));
        when(weChatMiniAppClient.getPhoneNumber("phone-code")).thenReturn("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.wxLogin(new WxLoginRequest("wx-code", null, "phone-code")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void adminLogin_totpEnabled_returnsChallengeInsteadOfToken() {
        // H22：短信验证码后台登录也必须过 2FA，不得直接签发正式 token
        UserInfo operator = operatorUser();
        operator.setTotpEnabled(true);
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(operator));
        when(smsCodeService.verifyCode(PHONE, "123456")).thenReturn(true);
        when(jwtService.createToken(operator.getUserId(), CabinetConstants.ACCOUNT_TYPE_OPERATOR))
                .thenReturn("session-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        when(userInfoRepository.findById(operator.getUserId())).thenReturn(Optional.of(operator));
        when(jwtService.createTwoFactorChallengeToken(operator.getUserId())).thenReturn("challenge");

        LoginResponse response = service.adminLogin(new LoginRequest(PHONE, "123456", null));

        assertEquals("challenge", response.token());
        assertEquals(operator.getUserId(), response.userId());
        assertTrue(response.twoFactorRequired());
        verify(jwtService).createTwoFactorChallengeToken(operator.getUserId());
    }

    @Test
    void adminLogin_totpDisabled_returnsSessionToken() {
        UserInfo operator = operatorUser();
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(operator));
        when(smsCodeService.verifyCode(PHONE, "123456")).thenReturn(true);
        when(jwtService.createToken(operator.getUserId(), CabinetConstants.ACCOUNT_TYPE_OPERATOR))
                .thenReturn("session-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        when(userInfoRepository.findById(operator.getUserId())).thenReturn(Optional.of(operator));

        LoginResponse response = service.adminLogin(new LoginRequest(PHONE, "123456", null));

        assertEquals("session-token", response.token());
        assertFalse(response.twoFactorRequired());
        verify(jwtService, never()).createTwoFactorChallengeToken(anyLong());
    }

    @Test
    void refreshSession_inactiveAccount_forbidden() {
        // H23：停用账号不得续期
        UserInfo user = consumerUser();
        user.setStatus("INACTIVE");
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refreshSession(100L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(ApiMessages.ACCOUNT_DISABLED, ex.getReason());
        verify(jwtService, never()).createToken(anyLong(), anyString());
    }

    private UserInfo operatorUser() {
        UserInfo user = new UserInfo();
        user.setUserId(CabinetConstants.OPERATOR_USER_ID_START);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_OPERATOR);
        user.setPhoneNumber(PHONE);
        user.setStatus("ACTIVE");
        return user;
    }
}
