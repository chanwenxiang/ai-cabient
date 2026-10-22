package com.aicabinet.trade.api;

import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.service.PayScoreOrderNotifyService;
import com.aicabinet.trade.service.PayScoreService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付分回调入口（授权结果 + 订单事件）。验签/时效/nonce 防重放/AES-GCM 解密全部复用
 * {@link WeChatPayNotifyService}——与支付结果通知同一套 v3 信任链，不另造轮子。
 *
 * <p>URL 即 {@code aicabinet.payscore.permission-notify-url} 指向的地址
 * （商户预授权/创单请求体里的 notify_url，CB-064）。回包约定与微信支付 v3 通知一致：204 无 body。</p>
 */
@RestController
@RequestMapping("/api/v2/payment/payscore")
public class PayScoreNotifyController {

    private final WeChatPayNotifyService notifyService;
    private final PayScoreService payScoreService;
    private final PayScoreOrderNotifyService payScoreOrderNotifyService;

    public PayScoreNotifyController(WeChatPayNotifyService notifyService,
                                    PayScoreService payScoreService,
                                    PayScoreOrderNotifyService payScoreOrderNotifyService) {
        this.notifyService = notifyService;
        this.payScoreService = payScoreService;
        this.payScoreOrderNotifyService = payScoreOrderNotifyService;
    }

    /** 支付分授权/订单事件通知。处理失败抛错 → 微信按策略重试；无主事件内部吞掉回 204。 */
    @PostMapping(value = "/notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> notify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        JsonNode event = notifyService.parseAndVerify(body, timestamp, nonce, signature, serial);
        if (PayScoreOrderNotifyService.isOrderEvent(event)) {
            payScoreOrderNotifyService.handleOrderNotify(event);
        } else {
            payScoreService.handleWechatPermissionNotify(event);
        }
        return ResponseEntity.noContent().build();
    }
}
