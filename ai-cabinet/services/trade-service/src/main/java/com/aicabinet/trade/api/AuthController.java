package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.LoginRequest;
import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.trade.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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

    @PostMapping("/wx-login")
    public ApiResponse<LoginResponse> wxLogin(@Valid @RequestBody com.aicabinet.common.dto.WxLoginRequest request) {
        return ApiResponse.ok(authService.wxLogin(request));
    }
}
