package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 仅开发环境可用：模拟支付宝支付成功 */
@RestController
@RequestMapping("/api/v2/payment/alipay")
@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")
public class DevMockAlipayPaymentController {

    private final PaymentService paymentService;

    public DevMockAlipayPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/notify/mock/{orderId}")
    public ApiResponse<Void> mockNotify(@PathVariable("orderId") String orderId,
                                        @RequestParam("userId") Long userId) {
        paymentService.confirmRechargeMock(userId, orderId);
        return ApiResponse.ok(null);
    }
}
