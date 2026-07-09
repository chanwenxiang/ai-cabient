package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.PasswordLoginRequest;
import com.aicabinet.common.dto.WxLoginRequest;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.service.AuthService;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/sms-code")
    public ApiResponse<Void> sendSms(@RequestParam("phoneNumber") String phoneNumber) {
        authService.sendSmsCode(phoneNumber);
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/admin-login")
    public ApiResponse<LoginResponse> adminLogin(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.adminLogin(request));
    }

    @PostMapping("/password-login")
    public ApiResponse<LoginResponse> passwordLogin(@Valid @RequestBody PasswordLoginRequest request) {
        return ApiResponse.ok(authService.loginByPassword(request));
    }

    @PostMapping("/admin-password-login")
    public ApiResponse<LoginResponse> adminPasswordLogin(@Valid @RequestBody PasswordLoginRequest request) {
        return ApiResponse.ok(authService.adminLoginByPassword(request));
    }

    @PostMapping("/wx-login")
    public ApiResponse<LoginResponse> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return ApiResponse.ok(authService.wxLogin(request));
    }

    @GetMapping("/server-boot")
    public ApiResponse<Long> serverBoot() {
        return ApiResponse.ok(authService.currentServerBootEpoch());
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.MISSING_TOKEN);
        }
        try {
            Long userId = jwtService.validateAndGetUserId(authorization.substring(7));
            return ApiResponse.ok(authService.refreshSession(userId));
        } catch (JwtService.InvalidSessionTokenException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        }
    }
}
