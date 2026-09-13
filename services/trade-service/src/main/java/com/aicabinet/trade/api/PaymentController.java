package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.common.dto.RechargePrepayResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/**
 * 消费者充值正式契约（冻结）。
 * <p>对外稳定路径：
 * <ul>
 *   <li>{@code POST /api/v2/payment/recharge/prepay}</li>
 *   <li>{@code GET /api/v2/payment/recharges}</li>
 *   <li>{@code GET /api/v2/payment/recharge/{orderId}}</li>
 *   <li>{@code POST /api/v2/payment/recharge/{orderId}/cancel}</li>
 * </ul>
 * 微信/支付宝真实异步回调在各自 NotifyController；
 * 本地模拟入账仅允许 {@code /api/v2/dev/payment/**}（{@code mock-enabled=true}）。
 * 变更本类公开签名须同步 OpenAPI、调用方与联调文档，禁止把 mock 路径挂回本前缀。
 */
@RestController
@RequestMapping("/api/v2/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/recharge/prepay")
    public ApiResponse<RechargePrepayResponse> rechargePrepay(
            HttpServletRequest request,
            @Valid @RequestBody RechargePrepayRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(paymentService.createRechargePrepay(userId, body.channel(),
                body.amountCents(), body.idempotencyKey()));
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

    public record RechargePrepayRequest(
            @NotBlank String channel,
            @Min(1) int amountCents,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) {}
}
