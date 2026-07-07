package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 仅开发环境可用：模拟微信支付成功 */
@RestController
@RequestMapping("/api/v2/payment/wechat")
@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")
public class DevMockPaymentController {

    private final PaymentService paymentService;

    public DevMockPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/notify/mock/{orderId}")
    public ApiResponse<Void> mockNotify(@PathVariable("orderId") String orderId) {
        paymentService.confirmRechargeMock(orderId);
        return ApiResponse.ok(null);
    }
}
