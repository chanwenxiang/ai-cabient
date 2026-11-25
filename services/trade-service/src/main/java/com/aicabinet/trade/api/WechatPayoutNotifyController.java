package com.aicabinet.trade.api;

import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.MerchantWithdrawService;
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
 * 微信「商家转账」回调入口（MA-407）：① 免确认授权结果；② 转账结果（确认收款模式到账）。
 *
 * <p>🔴 <b>为什么必须有回调</b>：
 * <ul>
 *   <li>授权接口把 {@code authorization_notify_url} 列为<b>必填</b>（官方 4015901167），
 *       不传直接 400 PARAM_ERROR（2026-11-23 B5 首跑实测）；</li>
 *   <li>确认收款模式下资金到账发生在<b>用户点确认的那一刻</b>，本地不接回调就只能等定时查单，
 *       商户端「到账了但页面还是待确认」——这是体验事故，也是对账盲区。</li>
 * </ul>
 *
 * <p>处理口径：验签 + 解密 + 防重放沿用 {@link WeChatPayNotifyService}（与支付/退款通知同一套
 * v3 链路）；落账走 {@link MerchantWithdrawService#finalizeFromChannel}（条件 UPDATE，幂等）；
 * 处理异常时归还 nonce 槽位让微信重投。
 */
@RestController
@RequestMapping("/api/v2/payout/wechat")
public class WechatPayoutNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WechatPayoutNotifyController.class);
    private static final int DETAIL_MAX = 200;

    private final WeChatPayNotifyService notifyService;
    private final AdminAuditService auditService;
    private final MerchantWithdrawService merchantWithdrawService;

    public WechatPayoutNotifyController(WeChatPayNotifyService notifyService,
                                        AdminAuditService auditService,
                                        MerchantWithdrawService merchantWithdrawService) {
        this.notifyService = notifyService;
        this.auditService = auditService;
        this.merchantWithdrawService = merchantWithdrawService;
    }

    /** 免确认授权结果通知：授权单长期有效，是「用户同意我们代打款」的唯一凭证 ⇒ 必须留痕。 */
    @PostMapping(value = "/authorization-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> authorizationNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Public-Key-Id", required = false) String publicKeyId) {
        String keyId = serial == null || serial.isBlank() ? publicKeyId : serial;
        JsonNode data = notifyService.parseAndVerify(body, timestamp, nonce, signature, keyId);
        String outAuthorizationNo = data.path("out_authorization_no").asText(null);
        String state = data.path("state").asText(null);
        String authorizationId = data.path("authorization_id").asText(null);
        log.info("wechat payout authorization notify: outAuthorizationNo={}, state={}, authorizationId={}",
                outAuthorizationNo, state, authorizationId);
        String detail = ("授权结果通知 state=" + state + " authorizationId=" + authorizationId
                + " openid=" + data.path("openid").asText(""));
        try {
            auditService.appendLog(null, "WECHAT_PAYOUT_AUTH_NOTIFY", "PAYOUT_AUTHORIZATION",
                    outAuthorizationNo, truncate(detail));
        } catch (RuntimeException e) {
            // 验签已消耗 nonce：留痕失败若不归还，微信重投会被判重放 ⇒ 这条授权永远补不上。
            notifyService.releaseNonce(nonce);
            throw e;
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 转账结果通知（确认收款模式）：用户确认收款后微信推送终态。
     *
     * <p>定位单据/判终态/落账全部在 {@link MerchantWithdrawService#handleTransferNotify}
     * （`..api..` 不得依赖 `..mapper..`）。只认 {@code out_bill_no}（= 我们的 idem_key）；
     * 查不到单据也回 2xx —— 回 4xx 会让微信反复重投一条我们永远处理不了的记录。
     */
    @PostMapping(value = "/transfer-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> transferNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Public-Key-Id", required = false) String publicKeyId) {
        String keyId = serial == null || serial.isBlank() ? publicKeyId : serial;
        JsonNode data = notifyService.parseAndVerify(body, timestamp, nonce, signature, keyId);
        String outBillNo = data.path("out_bill_no").asText(null);
        String state = data.path("state").asText(null);
        String transferBillNo = data.path("transfer_bill_no").asText(null);
        log.info("wechat transfer notify: outBillNo={}, state={}, transferBillNo={}",
                outBillNo, state, transferBillNo);
        try {
            merchantWithdrawService.handleTransferNotify(outBillNo, state, transferBillNo);
        } catch (RuntimeException e) {
            // 🔴 验签阶段已消耗 nonce；落账/查库失败如果不归还，微信的重投会被判「重放」而丢弃
            //    ⇒ 这笔到账永远补不上（只能靠定时查单兜，且商户侧一直是待确认）。
            //    归还后微信重投能真正重跑一遍（终态落账本身有条件 UPDATE 幂等闸，重复投不会双账）。
            notifyService.releaseNonce(nonce);
            log.error("wechat transfer notify 处理失败，nonce 已归还待重投: outBillNo={}, state={}, err={}",
                    outBillNo, state, e.toString());
            throw e;
        }
        return ResponseEntity.noContent().build();
    }

    private static String truncate(String detail) {
        return detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }
}
