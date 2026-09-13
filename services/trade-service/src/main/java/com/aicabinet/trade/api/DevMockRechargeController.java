package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/mock 专用：模拟充值支付成功。
 * 正式契约见 {@link PaymentController}（prepay / list / get / cancel）；本控制器不得并入正式路径。
 */
@RestController
@RequestMapping("/api/v2/dev/payment")
@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")
public class DevMockRechargeController {

    private final PaymentService paymentService;

    public DevMockRechargeController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/recharge/{orderId}/mock-success")
    public ApiResponse<RechargeOrderDto> confirmMockRecharge(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(paymentService.confirmRechargeMock(userId, orderId));
    }
}
