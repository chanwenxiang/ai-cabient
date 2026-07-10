package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.PasswordLoginRequest;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
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

    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final SmsCodeService smsCodeService;
    private final PasswordEncoder passwordEncoder;
    private final ServerBootMarker serverBootMarker;

    public AuthService(UserInfoRepository userInfoRepository,
                       UserAccountRepository userAccountRepository,
                       JwtService jwtService,
                       WeChatMiniAppClient weChatMiniAppClient,
                       SmsCodeService smsCodeService,
                       PasswordEncoder passwordEncoder,
                       ServerBootMarker serverBootMarker) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.jwtService = jwtService;
        this.weChatMiniAppClient = weChatMiniAppClient;
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
        return response;
    }

    @Transactional
    public LoginResponse adminLoginByPassword(PasswordLoginRequest request) {
        LoginResponse response = loginByPassword(request);
        requireOperator(response.userId());
        return response;
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
