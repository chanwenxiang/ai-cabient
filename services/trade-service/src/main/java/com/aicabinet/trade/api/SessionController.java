package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.LiveCartDto;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.SessionCartRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SecurityProperties securityProperties;

    public SessionController(SessionService sessionService, SecurityProperties securityProperties) {
        this.sessionService = sessionService;
        this.securityProperties = securityProperties;
    }

    @PostMapping
    public ApiResponse<SessionDto> create(
            HttpServletRequest request,
            @Valid @RequestBody CreateSessionRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.createSession(userId, body));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDto> get(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.getSession(userId, sessionId));
    }

    @GetMapping("/active")
    public ApiResponse<SessionDto> active(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.getActiveSession(userId));
    }

    @PostMapping("/{sessionId}/cancel")
    public ApiResponse<SessionDto> cancel(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.cancelSession(userId, sessionId));
    }

    @PutMapping("/{sessionId}/cart")
    public ApiResponse<SessionDto> updateCart(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody SessionCartRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.updateSessionCart(userId, sessionId, body));
    }

    /** 演示关门结算（仅 mockEnabled 环境开放，模拟柜机上报关门事件）。 */
    @PostMapping("/{sessionId}/demo-close")
    public ApiResponse<SessionDto> demoClose(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "演示关门结算未开启");
        }
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.demoCloseSession(userId, sessionId));
    }

    @GetMapping("/{sessionId}/order")
    public ApiResponse<OrderDto> getOrder(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.getSessionOrder(userId, sessionId));
    }

    /** 开门中实时购物车（第三方识别推送快照；结算仍以关门识别为准） */
    @GetMapping("/{sessionId}/live-cart")
    public ApiResponse<LiveCartDto> liveCart(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.getLiveCart(userId, sessionId));
    }
}
