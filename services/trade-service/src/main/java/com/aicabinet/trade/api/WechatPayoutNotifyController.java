package com.aicabinet.trade.api;

import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
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

import java.util.Optional;

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
    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final MerchantWithdrawService merchantWithdrawService;

    public WechatPayoutNotifyController(WeChatPayNotifyService notifyService,
                                        AdminAuditService auditService,
                                        MerchantWithdrawRequestMapper withdrawMapper,
                                        MerchantWithdrawService merchantWithdrawService) {
        this.notifyService = notifyService;
        this.auditService = auditService;
        this.withdrawMapper = withdrawMapper;
        this.merchantWithdrawService = merchantWithdrawService;
    }

    /** 免确认授权结果通知：授权单长期有效，是「用户同意我们代打款」的唯一凭证 ⇒ 必须留痕。 */
    @PostMapping(value = "/authorization-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> authorizationNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        JsonNode data = verifyOrRelease(body, timestamp, nonce, signature, serial);
        String outAuthorizationNo = data.path("out_authorization_no").asText(null);
        String state = data.path("state").asText(null);
        String authorizationId = data.path("authorization_id").asText(null);
        log.info("wechat payout authorization notify: outAuthorizationNo={}, state={}, authorizationId={}",
                outAuthorizationNo, state, authorizationId);
        String detail = ("授权结果通知 state=" + state + " authorizationId=" + authorizationId
                + " openid=" + data.path("openid").asText(""));
        auditService.appendLog(null, "WECHAT_PAYOUT_AUTH_NOTIFY", "PAYOUT_AUTHORIZATION",
                outAuthorizationNo, truncate(detail));
        return ResponseEntity.noContent().build();
    }

    /**
     * 转账结果通知（确认收款模式）：用户确认收款后微信推送终态。
     *
     * <p>只认 {@code out_bill_no}（= 我们的 idem_key）；查不到单据也回 2xx ——
     * 回 4xx 会让微信反复重投一条我们永远处理不了的记录。
     */
    @PostMapping(value = "/transfer-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> transferNotify(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        JsonNode data = verifyOrRelease(body, timestamp, nonce, signature, serial);
        String outBillNo = data.path("out_bill_no").asText(null);
        String state = data.path("state").asText(null);
        String transferBillNo = data.path("transfer_bill_no").asText(null);
        log.info("wechat transfer notify: outBillNo={}, state={}, transferBillNo={}",
                outBillNo, state, transferBillNo);
        Optional<MerchantWithdrawRequest> hit = outBillNo == null
                ? Optional.empty() : withdrawMapper.findByIdemKey(outBillNo);
        if (hit.isEmpty()) {
            log.warn("wechat transfer notify 未匹配到提现单: outBillNo={}, state={}", outBillNo, state);
            auditService.appendLog(null, "WECHAT_PAYOUT_NOTIFY_UNMATCHED", "MERCHANT_WITHDRAW",
                    outBillNo, truncate("转账结果通知 state=" + state + "（无对应提现单）"));
            return ResponseEntity.noContent().build();
        }
        MerchantWithdrawRequest request = hit.get();
        if ("SUCCESS".equals(state)) {
            merchantWithdrawService.finalizeFromChannel(request.getRequestId(), true, transferBillNo,
                    "微信转账到账（用户已确认收款）");
        } else if ("FAIL".equals(state) || "CANCELLED".equals(state) || "CANCELING".equals(state)) {
            merchantWithdrawService.finalizeFromChannel(request.getRequestId(), false, transferBillNo,
                    "微信转账终态=" + state + "（回调）");
        } else {
            // 非终态（WAIT_USER_CONFIRM/ACCEPTED/PROCESSING/TRANSFERING）：不动单据，等下一次通知或查单
            log.info("wechat transfer notify 非终态忽略: requestId={}, state={}",
                    request.getRequestId(), state);
        }
        return ResponseEntity.noContent().build();
    }

    private JsonNode verifyOrRelease(String body, String timestamp, String nonce,
                                     String signature, String serial) {
        try {
            return notifyService.parseAndVerify(body, timestamp, nonce, signature, serial);
        } catch (RuntimeException e) {
            notifyService.releaseNonce(nonce);
            throw e;
        }
    }

    private static String truncate(String detail) {
        return detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }
}
