package com.aicabinet.trade.api;

import com.aicabinet.common.dto.AlipayLoginRequest;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AdminPasswordResetRequest;
import com.aicabinet.common.dto.CaptchaResponse;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.PasswordLoginRequest;
import com.aicabinet.common.dto.RecoveryTwoFactorRequest;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.common.dto.VerifyTwoFactorRequest;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.SessionCookieService;
import com.aicabinet.trade.service.AuthService;
import com.aicabinet.trade.service.CaptchaService;
import com.aicabinet.trade.service.OpsTwoFactorService;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final CaptchaService captchaService;
    private final SessionCookieService sessionCookieService;
    private final OpsTwoFactorService opsTwoFactorService;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          CaptchaService captchaService,
                          SessionCookieService sessionCookieService,
                          OpsTwoFactorService opsTwoFactorService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.sessionCookieService = sessionCookieService;
        this.opsTwoFactorService = opsTwoFactorService;
    }

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> captcha() {
        return ApiResponse.ok(captchaService.create());
    }

    @PostMapping("/sms-code")
    public ApiResponse<Void> sendSms(@RequestParam("phoneNumber") String phoneNumber) {
        authService.sendSmsCode(phoneNumber);
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.login(request)));
    }

    @PostMapping("/admin-login")
    public ApiResponse<LoginResponse> adminLogin(@Valid @RequestBody LoginRequest request,
                                                 HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.adminLogin(request)));
    }

    @PostMapping("/password-login")
    public ApiResponse<LoginResponse> passwordLogin(@Valid @RequestBody PasswordLoginRequest request,
                                                    HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.loginByPassword(request)));
    }

    @PostMapping("/admin-password-login")
    public ApiResponse<LoginResponse> adminPasswordLogin(@Valid @RequestBody PasswordLoginRequest request,
                                                         HttpServletResponse response) {
        captchaService.verifyOrThrow(request.captchaId(), request.captchaCode());
        return ApiResponse.ok(withSessionCookie(response, authService.adminLoginByPassword(request)));
    }

    /** 双因子认证：动态码完成登录（challengeToken 由 admin-password-login 返回）。 */
    @PostMapping("/admin-2fa/verify")
    public ApiResponse<LoginResponse> verifyTwoFactor(@Valid @RequestBody VerifyTwoFactorRequest request,
                                                      HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response,
                opsTwoFactorService.verifyChallenge(request.challengeToken(), request.code())));
    }

    /** 双因子认证：后备码完成登录。 */
    @PostMapping("/admin-2fa/recovery")
    public ApiResponse<LoginResponse> recoveryTwoFactor(@Valid @RequestBody RecoveryTwoFactorRequest request,
                                                        HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response,
                opsTwoFactorService.verifyRecovery(request.challengeToken(), request.recoveryCode())));
    }

    /** 运营后台忘记密码：短信验证码 + 图形验证码后重置密码。 */
    @PostMapping("/admin-password-reset")
    public ApiResponse<Void> adminPasswordReset(@Valid @RequestBody AdminPasswordResetRequest request) {
        captchaService.verifyOrThrow(request.captchaId(), request.captchaCode());
        authService.adminResetPassword(request);
        return ApiResponse.ok(null);
    }

    /** 商户端密码登录：同运营鉴权边界，但不要求图形验证码。 */
    @PostMapping("/merchant-password-login")
    public ApiResponse<LoginResponse> merchantPasswordLogin(@Valid @RequestBody PasswordLoginRequest request,
                                                            HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.adminLoginByPassword(request)));
    }

    @PostMapping("/wx-login")
    public ApiResponse<LoginResponse> wxLogin(@Valid @RequestBody WxLoginRequest request,
                                              HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.wxLogin(request)));
    }

    /** H5 微信网页授权登录（公众号 OAuth code）。 */
    @PostMapping("/wx-h5-login")
    public ApiResponse<LoginResponse> wxH5Login(@Valid @RequestBody WxLoginRequest request,
                                                HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(
                response, authService.wxH5Login(request.code(), request.phoneNumber())));
    }

    @PostMapping("/alipay/login")
    public ApiResponse<LoginResponse> alipayLogin(@Valid @RequestBody AlipayLoginRequest request,
                                                  HttpServletResponse response) {
        return ApiResponse.ok(withSessionCookie(response, authService.alipayLogin(request)));
    }

    @GetMapping("/server-boot")
    public ApiResponse<Long> serverBoot() {
        return ApiResponse.ok(authService.currentServerBootEpoch());
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request,
            HttpServletResponse response) {
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        if (token == null) {
            token = sessionCookieService.resolveToken(request);
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.MISSING_TOKEN);
        }
        try {
            Long userId = jwtService.validateAndGetUserId(token);
            LoginResponse refreshed = authService.refreshSession(userId);
            return ApiResponse.ok(withSessionCookie(response, refreshed));
        } catch (JwtService.InvalidSessionTokenException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        }
    }

    /** 登出：清除会话 Cookie（幂等；纯 Bearer 客户端调用无副作用）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        sessionCookieService.clearSessionCookie(response);
        return ApiResponse.ok(null);
    }

    private LoginResponse withSessionCookie(HttpServletResponse response, LoginResponse loginResponse) {
        sessionCookieService.writeSessionCookie(response, loginResponse.token());
        return loginResponse;
    }
}
