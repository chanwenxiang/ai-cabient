package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.aicabinet.trade.wechat.MiniAppTarget;

/**
 * CB-048：商户端微信绑定 + 免密登录。
 * - 绑定必须在已验证商户会话内（CONSUMER 拒绝）；openid 全局唯一归属，抢绑 409；覆盖语义=换绑。
 * - 免密登录与 C 端 wxLogin 有意不同：不做自动建档；openid 命中游客账号同样拒绝；
 *   2FA 商户回 challenge（与 /merchant-password-login 同口径）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceMerchantWxTest {

    private static final String OPEN_ID = "openid-merchant-1";
    private static final String OPEN_ID_NEW = "openid-merchant-2";
    private static final long MERCHANT_ID = 20001L; // 运营号段（≥ OPERATOR_USER_ID_START）

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
                new AuthProperties("test-secret", 3600L, false, false, 5, 15, null, AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS),
                new SecurityProperties(false),
                loginThrottleService, phoneVerifyLogMapper, distributedLockService,
                new UserConsentLogService(org.mockito.Mockito.mock(com.aicabinet.trade.mapper.UserConsentLogMapper.class)), null);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private UserInfo merchant() {
        UserInfo user = new UserInfo();
        user.setUserId(MERCHANT_ID);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_OPERATOR);
        user.setStatus("ACTIVE");
        return user;
    }

    private UserInfo consumer() {
        UserInfo user = new UserInfo();
        user.setUserId(100L);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_CONSUMER);
        user.setStatus("ACTIVE");
        return user;
    }

    private void stubCode2Session(String openId) {
        when(weChatMiniAppClient.code2Session(anyString(), eq(MiniAppTarget.MERCHANT)))
                .thenReturn(new WeChatMiniAppClient.Code2SessionResult(openId, "session"));
    }

    // ---------- bindMerchantWeChat ----------

    @Test
    void bind_换绑成功_新openid覆盖写入() {
        stubCode2Session(OPEN_ID_NEW);
        UserInfo me = merchant();
        me.setWxOpenId(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID_NEW)).thenReturn(Optional.empty());
        when(userInfoRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(me));

        service.bindMerchantWeChat(MERCHANT_ID, "code");

        assertEquals(OPEN_ID_NEW, me.getWxOpenId());
        verify(userInfoRepository).save(me);
    }

    @Test
    void bind_重复绑同一微信_幂等不写库() {
        stubCode2Session(OPEN_ID);
        UserInfo me = merchant();
        me.setWxOpenId(OPEN_ID);
        when(userInfoRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(me));

        service.bindMerchantWeChat(MERCHANT_ID, "code");

        verify(userInfoRepository, never()).save(any());
    }

    @Test
    void bind_openid已被他人占用_409() {
        stubCode2Session(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.of(consumer()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.bindMerchantWeChat(MERCHANT_ID, "code"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(userInfoRepository, never()).save(any());
    }

    @Test
    void bind_消费者账号_403() {
        stubCode2Session(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.empty());
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(consumer()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.bindMerchantWeChat(100L, "code"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    // ---------- merchantWxLogin ----------

    @Test
    void wxLogin_未绑定_404并提示走密码登录() {
        stubCode2Session(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.merchantWxLogin(new WxLoginRequest("code", null, null)));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains("密码登录"));
    }

    @Test
    void wxLogin_命中游客账号_403() {
        stubCode2Session(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.of(consumer()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.merchantWxLogin(new WxLoginRequest("code", null, null)));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void wxLogin_绑定商户_签发token() {
        stubCode2Session(OPEN_ID);
        UserInfo me = merchant();
        me.setWxOpenId(OPEN_ID);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.of(me));
        when(userInfoRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(me)); // requireActiveAccount
        when(jwtService.createToken(eq(MERCHANT_ID), anyString())).thenReturn("jwt-token");

        LoginResponse resp = service.merchantWxLogin(new WxLoginRequest("code", null, null));

        assertEquals("jwt-token", resp.token());
        assertEquals(MERCHANT_ID, resp.userId());
        verify(jwtService, never()).createTwoFactorChallengeToken(anyLong());
    }

    @Test
    void wxLogin_2FA商户_回challenge不发正式token() {
        stubCode2Session(OPEN_ID);
        UserInfo me = merchant();
        me.setWxOpenId(OPEN_ID);
        me.setTotpEnabled(true);
        when(userInfoRepository.findByWxOpenId(OPEN_ID)).thenReturn(Optional.of(me));
        when(userInfoRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(me)); // requireActiveAccount
        when(jwtService.createTwoFactorChallengeToken(MERCHANT_ID)).thenReturn("challenge");

        LoginResponse resp = service.merchantWxLogin(new WxLoginRequest("code", null, null));

        assertEquals("challenge", resp.token());
        assertTrue(resp.twoFactorRequired());
        verify(jwtService, never()).createToken(anyLong(), anyString());
    }
}
