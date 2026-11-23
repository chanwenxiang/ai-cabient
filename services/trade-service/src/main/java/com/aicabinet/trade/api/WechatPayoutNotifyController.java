package com.aicabinet.trade.api;

import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.service.AdminAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信「商家转账」免确认收款授权结果通知（MA-407）。
 *
 * <p>🔴 <b>为什么必须有这个端点</b>：官方文档 4015901167 把 {@code authorization_notify_url}
 * 列为<b>必填</b>——不传直接 400 PARAM_ERROR（2026-11-23 B5 首跑实测）。而它一旦是可公网访问的
 * HTTPS 地址，微信就会真的往里投通知；端点不存在/不验签/非 2xx 都会触发渠道重投与告警。
 *
 * <p>处理口径：
 * <ul>
 *   <li>验签 + 解密 + 防重放沿用 {@link WeChatPayNotifyService}（与支付/退款通知同一套 v3 链路）；</li>
 *   <li>授权状态落 {@code admin_audit_log}（调用方为渠道 ⇒ operator=系统 0）：转账按
 *       {@code out_authorization_no} 确定性派生，运行期不需要读这张表，但**排障与合规要能查到
 *       「用户何时授权/解除了授权」**，否则用户说「我没同意过」时无从举证；</li>
 *   <li>异步通知允许微信重投 ⇒ 处理异常时归还 nonce 槽位，避免 10 分钟 TTL 内每条重投都被判重放。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/payout/wechat")
public class WechatPayoutNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WechatPayoutNotifyController.class);
    private static final int DETAIL_MAX = 200;

    private final WeChatPayNotifyService notifyService;
    private final AdminAuditService auditService;

    public WechatPayoutNotifyController(WeChatPayNotifyService notifyService,
                                        AdminAuditService auditService) {
        this.notifyService = notifyService;
        this.auditService = auditService;
    }

    @PostMapping(value = "/authorization-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> authorizationNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        JsonNode data;
        try {
            data = notifyService.parseAndVerify(body, timestamp, nonce, signature, serial);
        } catch (RuntimeException e) {
            notifyService.releaseNonce(nonce);
            throw e;
        }
        String outAuthorizationNo = data.path("out_authorization_no").asText(null);
        String state = data.path("state").asText(null);
        String authorizationId = data.path("authorization_id").asText(null);
        log.info("wechat payout authorization notify: outAuthorizationNo={}, state={}, authorizationId={}",
                outAuthorizationNo, state, authorizationId);
        String detail = ("授权结果通知 state=" + state + " authorizationId=" + authorizationId
                + " openid=" + data.path("openid").asText(""));
        auditService.appendLog(null, "WECHAT_PAYOUT_AUTH_NOTIFY", "PAYOUT_AUTHORIZATION",
                outAuthorizationNo,
                detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail);
        return ResponseEntity.noContent().build();
    }
}
