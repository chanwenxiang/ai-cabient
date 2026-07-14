package com.aicabinet.trade.api;

import com.aicabinet.trade.service.PaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/payment/wechat")
public class WeChatNotifyController {

    private final PaymentService paymentService;

    public WeChatNotifyController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 微信支付 V3 结果通知（JSON + 应答无 body） */
    @PostMapping(value = "/notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> notify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        paymentService.handleWeChatNotify(body, timestamp, nonce, signature, serial);
        return ResponseEntity.noContent().build();
    }
}
