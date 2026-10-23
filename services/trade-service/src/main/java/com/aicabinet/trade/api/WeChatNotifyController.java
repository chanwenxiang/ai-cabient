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

    /**
     * 微信支付 V3 退款结果通知（refund-notify）。
     * 商户平台手工退款此前是系统盲区（无回调地址入口，靠每日对账查单兜底），
     * 本端点接收退款结果并同步扣减用户余额，防止幽灵余额（2026-10-23 实证案例）。
     * 验签/解密/防重放复用 {@link com.aicabinet.trade.payment.WeChatPayNotifyService}。
     */
    @PostMapping(value = "/refund-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> refundNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        paymentService.handleWeChatRefundNotify(body, timestamp, nonce, signature, serial);
        return ResponseEntity.noContent().build();
    }
}
