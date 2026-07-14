package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.SessionCartRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ApiResponse<SessionDto> create(
            HttpServletRequest request,
            @Valid @RequestBody CreateSessionRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        CreateSessionRequest req = new CreateSessionRequest(body.deviceId(), body.idempotencyKey());
        return ApiResponse.ok(sessionService.createSession(userId, req));
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

    @GetMapping("/{sessionId}/order")
    public ApiResponse<OrderDto> getOrder(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sessionService.getSessionOrder(userId, sessionId));
    }
}
