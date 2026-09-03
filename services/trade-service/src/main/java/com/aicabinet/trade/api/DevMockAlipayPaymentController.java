package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅 mock 开启时可用：模拟支付宝支付成功（须登录；userId 取自登录态，禁止参数伪造）。 */
@RestController
@RequestMapping("/api/v2/payment/alipay")
@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")
public class DevMockAlipayPaymentController {

    private final PaymentService paymentService;

    public DevMockAlipayPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/notify/mock/{orderId}")
    public ApiResponse<Void> mockNotify(HttpServletRequest request, @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        paymentService.confirmRechargeMock(userId, orderId);
        return ApiResponse.ok(null);
    }
}
