package com.aicabinet.trade.service;



import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.LoginRequest;

import com.aicabinet.common.dto.LoginResponse;

import com.aicabinet.common.dto.WxLoginRequest;

import com.aicabinet.trade.auth.JwtService;

import com.aicabinet.trade.domain.UserAccount;

import com.aicabinet.trade.domain.UserInfo;

import com.aicabinet.trade.repository.UserAccountRepository;

import com.aicabinet.trade.repository.UserInfoRepository;

import com.aicabinet.trade.sms.SmsCodeService;

import com.aicabinet.trade.support.ApiMessages;

import com.aicabinet.trade.wechat.WeChatMiniAppClient;

import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;



import java.util.Optional;



@Service

public class AuthService {



    private final UserInfoRepository userInfoRepository;

    private final UserAccountRepository userAccountRepository;

    private final JwtService jwtService;

    private final WeChatMiniAppClient weChatMiniAppClient;

    private final SmsCodeService smsCodeService;



    public AuthService(UserInfoRepository userInfoRepository,

                       UserAccountRepository userAccountRepository,

                       JwtService jwtService,

                       WeChatMiniAppClient weChatMiniAppClient,

                       SmsCodeService smsCodeService) {

        this.userInfoRepository = userInfoRepository;

        this.userAccountRepository = userAccountRepository;

        this.jwtService = jwtService;

        this.weChatMiniAppClient = weChatMiniAppClient;

        this.smsCodeService = smsCodeService;

    }



    public void sendSmsCode(String phoneNumber) {

        String phone = normalizePhone(phoneNumber);

        if (!phone.matches("1\\d{10}")) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);

        }

        smsCodeService.sendCode(phone);

    }



    @Transactional

    public LoginResponse login(LoginRequest request) {

        String phone = normalizePhone(request.phoneNumber());

        String code = normalizeCode(request.code());

        if (!phone.matches("1\\d{10}")) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);

        }

        if (!smsCodeService.verifyCode(phone, code)) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_CODE);

        }

        UserInfo user = userInfoRepository.findByPhoneNumber(phone)

                .orElseGet(() -> registerNewUser(phone));

        return tokenFor(user);

    }



    /** 运营后台登录：拒绝消费者账号 */

    @Transactional

    public LoginResponse adminLogin(LoginRequest request) {

        LoginResponse response = login(request);

        if (response.userId() < CabinetConstants.OPERATOR_USER_ID_START) {

            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.CONSUMER_CANNOT_USE_ADMIN);

        }

        return response;

    }



    /** 微信小程序 wx.login code 换 openId 并登录 */

    @Transactional

    public LoginResponse wxLogin(WxLoginRequest request) {

        var session = weChatMiniAppClient.code2Session(request.code());

        Optional<UserInfo> byOpenId = userInfoRepository.findByWxOpenId(session.openId());

        UserInfo user;

        if (byOpenId.isPresent()) {

            user = byOpenId.get();

        } else if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {

            String phone = normalizePhone(request.phoneNumber());

            user = userInfoRepository.findByPhoneNumber(phone)

                    .orElseGet(() -> registerNewUser(phone));

            user.setWxOpenId(session.openId());

            userInfoRepository.save(user);

        } else {

            user = registerWxUser(session.openId());

        }

        return tokenFor(user);

    }



    private UserInfo registerWxUser(String openId) {

        UserInfo user = new UserInfo();

        long userId = System.currentTimeMillis() % 1_000_000_000L;

        user.setUserId(userId);

        user.setPhoneNumber("wx_" + openId.substring(0, Math.min(8, openId.length())));

        user.setWxOpenId(openId);

        user.setVerified(false);

        userInfoRepository.save(user);

        UserAccount account = new UserAccount();

        account.setUserId(userId);

        account.setBalanceCents(0);

        userAccountRepository.save(account);

        return user;

    }



    private UserInfo registerNewUser(String phone) {

        UserInfo user = new UserInfo();

        long userId = System.currentTimeMillis() % 1_000_000_000L;

        user.setUserId(userId);

        user.setPhoneNumber(phone);

        user.setVerified(false);

        userInfoRepository.save(user);

        UserAccount account = new UserAccount();

        account.setUserId(userId);

        account.setBalanceCents(0);

        userAccountRepository.save(account);

        return user;

    }



    private LoginResponse tokenFor(UserInfo user) {

        String token = jwtService.createToken(user.getUserId());

        return new LoginResponse(token, user.getUserId(), jwtService.getExpirationSeconds());

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


