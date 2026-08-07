package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.AdminPasswordResetRequest;
import com.aicabinet.common.dto.PasswordLoginRequest;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.common.dto.AlipayLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayOauthClient;
import com.aicabinet.trade.sms.SmsCodeService;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.ServerBootMarker;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final JwtService jwtService;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final AlipayOauthClient alipayOauthClient;
    private final SmsCodeService smsCodeService;
    private final PasswordEncoder passwordEncoder;
    private final ServerBootMarker serverBootMarker;

    public AuthService(UserInfoMapper userInfoRepository,
                       UserAccountMapper userAccountRepository,
                       JwtService jwtService,
                       WeChatMiniAppClient weChatMiniAppClient,
                       AlipayOauthClient alipayOauthClient,
                       SmsCodeService smsCodeService,
                       PasswordEncoder passwordEncoder,
                       ServerBootMarker serverBootMarker) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.jwtService = jwtService;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.alipayOauthClient = alipayOauthClient;
        this.smsCodeService = smsCodeService;
        this.passwordEncoder = passwordEncoder;
        this.serverBootMarker = serverBootMarker;
    }

    public void sendSmsCode(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        if (!phone.matches("1\\d{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        requireExistingUser(phone);
        smsCodeService.sendCode(phone);
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String phone = normalizePhone(request.phoneNumber());
        String code = normalizeCode(request.code());
        if (!phone.matches("1\\d{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        UserInfo user = requireExistingUser(phone);
        if (!smsCodeService.verifyCode(phone, code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_CODE);
        }
        return tokenFor(user);
    }

    @Transactional
    public LoginResponse loginByPassword(PasswordLoginRequest request) {
        String phone = normalizePhone(request.phoneNumber());
        if (!phone.matches("1\\d{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        UserInfo user = requireExistingUser(phone);
        verifyPassword(user, request.password());
        return tokenFor(user);
    }

    /** 运营后台登录：拒绝消费者账号 */
    @Transactional
    public LoginResponse adminLogin(LoginRequest request) {
        LoginResponse response = login(request);
        requireOperator(response.userId());
        requireActiveAccount(response.userId());
        return response;
    }

    @Transactional
    public LoginResponse adminLoginByPassword(PasswordLoginRequest request) {
        LoginResponse response = loginByPassword(request);
        requireOperator(response.userId());
        requireActiveAccount(response.userId());
        return response;
    }

    /** 运营后台忘记密码：短信验证码 + 图形验证码校验后重置。 */
    @Transactional
    public void adminResetPassword(AdminPasswordResetRequest request) {
        String phone = normalizePhone(request.phoneNumber());
        if (!phone.matches("1\\d{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        String newPassword = request.newPassword();
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度需在 6-64 位之间");
        }
        UserInfo user = requireExistingUser(phone);
        requireOperator(user.getUserId());
        requireActiveAccount(user.getUserId());
        if (!smsCodeService.verifyCode(phone, request.smsCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_CODE);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userInfoRepository.save(user);
    }

    /** 微信小程序 wx.login：已绑定 openId 直接登录；否则自动建档（竞品扫码免注册） */
    @Transactional
    public LoginResponse wxLogin(WxLoginRequest request) {
        var session = weChatMiniAppClient.code2Session(request.code());
        var byOpenId = userInfoRepository.findByWxOpenId(session.openId());
        if (byOpenId.isPresent()) {
            return tokenFor(byOpenId.get());
        }
        String phone = request.phoneNumber() != null ? normalizePhone(request.phoneNumber()) : "";
        if (!phone.isBlank()) {
            if (!phone.matches("1\\d{10}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
            }
            UserInfo user = requireExistingUser(phone);
            user.setWxOpenId(session.openId());
            userInfoRepository.save(user);
            return tokenFor(user);
        }
        return tokenFor(createWxConsumer(session.openId()));
    }

    /** 支付宝 H5 授权：已绑定 user_id 直接登录；否则自动建档 */
    @Transactional
    public LoginResponse alipayLogin(AlipayLoginRequest request) {
        String alipayUserId = alipayOauthClient.resolveUserId(request.authCode());
        var byAlipay = userInfoRepository.findByAlipayUserId(alipayUserId);
        if (byAlipay.isPresent()) {
            return tokenFor(byAlipay.get());
        }
        return tokenFor(createAlipayConsumer(alipayUserId));
    }

    private UserInfo createWxConsumer(String openId) {
        Long userId = userInfoRepository.nextConsumerUserId(CabinetConstants.OPERATOR_USER_ID_START);
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setPhoneNumber("wx" + userId);
        user.setName("微信用户");
        user.setVerified(false);
        user.setWxOpenId(openId);
        userInfoRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalanceCents(0);
        userAccountRepository.save(account);
        return user;
    }

    private UserInfo createAlipayConsumer(String alipayUserId) {
        Long userId = userInfoRepository.nextConsumerUserId(CabinetConstants.OPERATOR_USER_ID_START);
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setPhoneNumber("ali" + userId);
        user.setName("支付宝用户");
        user.setVerified(false);
        user.setAlipayUserId(alipayUserId);
        user.setPayPreferredChannel("ALIPAY");
        userInfoRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalanceCents(0);
        userAccountRepository.save(account);
        return user;
    }

    private UserInfo requireExistingUser(String phone) {
        return userInfoRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
    }

    private void verifyPassword(UserInfo user, String rawPassword) {
        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.PASSWORD_NOT_SET);
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PASSWORD);
        }
    }

    private static void requireOperator(long userId) {
        if (userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.CONSUMER_CANNOT_USE_ADMIN);
        }
    }

    private void requireActiveAccount(long userId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        if (user.getStatus() != null && "INACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCOUNT_DISABLED);
        }
    }

    private LoginResponse tokenFor(UserInfo user) {
        String token = jwtService.createToken(user.getUserId());
        return new LoginResponse(token, user.getUserId(), jwtService.getExpirationSeconds(),
                serverBootMarker.epochMillis());
    }

    public long currentServerBootEpoch() {
        return serverBootMarker.epochMillis();
    }

    /** 在 token 仍有效时续期（滑动过期，活跃用户不中断操作） */
    @Transactional(readOnly = true)
    public LoginResponse refreshSession(Long userId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN));
        return tokenFor(user);
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("\\s", "");
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim();
    }
}
