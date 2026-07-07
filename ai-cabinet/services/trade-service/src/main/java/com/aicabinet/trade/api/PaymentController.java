package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeRequest;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.common.dto.WxPayParams;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/recharge/prepay")
    public ApiResponse<WxPayParams> rechargePrepay(
            HttpServletRequest request,
            @Valid @RequestBody RechargeRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String clientIp = request.getRemoteAddr();
        return ApiResponse.ok(paymentService.createRechargePrepay(userId, body, clientIp));
    }

    @GetMapping("/recharges")
    public ApiResponse<PageResult<RechargeOrderDto>> listRecharges(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(paymentService.listMyRecharges(userId, page, size));
    }

    @GetMapping("/recharge/{orderId}")
    public ApiResponse<RechargeOrderDto> getRecharge(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(paymentService.getRechargeOrder(userId, orderId));
    }

    @PostMapping("/recharge/{orderId}/cancel")
    public ApiResponse<RechargeOrderDto> cancelRecharge(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(paymentService.cancelRecharge(userId, orderId));
    }
}
