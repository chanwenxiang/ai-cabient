package com.aicabinet.trade.payout;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatTransferProperties;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信「商家转账」API 客户端（MA-407，用户授权免确认模式）。
 *
 * <p>三个动作：① {@link #requestAuthorization} 发起免确认授权（返回 package_info 供小程序拉起）；
 * ② {@link #transfer} 授权后转账（state=SUCCESS 为终态）；③ {@link #queryByOutBillNo} 查单兜底。
 *
 * <p>🔴 <b>幂等纪律</b>（官方 4014399371）：HTTP 非 200 / 未知错误码时，<b>绝不能换商户单号重试</b>——
 * 必须原 out_bill_no 原参数重试或先查单确认失败，否则重复出款。故本类把「结果不确定」统一抛
 * {@link PayoutChannel.PayoutPendingException}，由上层保持 PAYING 转人工/查单推进，绝不返回 success。
 *
 * <p>🔴 <b>敏感字段纪律</b>：B5/生产提现额 < ¥2000，不传 user_name（官方文档：≥2000 才强制姓名校验），
 * 因此无需微信公钥加密敏感字段；一旦将来要传姓名，必须补 Wechatpay-Serial 头 + RSA/OAEP 加密，
 * 否则接口会拒。本类 fail-closed：金额 ≥2000 且姓名缺失时直接拒绝，不静默少传。
 */
@Component
public class WeChatTransferClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatTransferClient.class);

    /** 授权申请：待用户确认（一次性，24h 内有效）。 */
    public static final String AUTH_WAIT = "WAIT_USER_CONFIRM";
    /** 授权生效：长期有效，可反复转账。 */
    public static final String AUTH_EFFECTIVE = "TAKING_EFFECT";

    private final WeChatPayV3Client v3;
    private final WeChatPayProperties payProperties;
    private final WeChatTransferProperties properties;

    public WeChatTransferClient(WeChatPayV3Client v3, WeChatPayProperties payProperties,
                                WeChatTransferProperties properties) {
        this.v3 = v3;
        this.payProperties = payProperties;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.enabled() && payProperties.isConfigured();
    }

    /** 发起免确认收款授权。返回 package_info（供小程序 JSAPI 拉起授权页）。 */
    public AuthorizationRequest requestAuthorization(String openid, String displayName,
                                                      String outAuthorizationNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_authorization_no", outAuthorizationNo);
        body.put("appid", payProperties.appId());
        body.put("openid", openid);
        body.put("transfer_scene_id", properties.sceneId());
        body.put("user_display_name", displayName);
        if (properties.userRecvPerception() != null && !properties.userRecvPerception().isBlank()) {
            body.put("user_recv_perception", properties.userRecvPerception().trim());
        }
        if (properties.authorizationNotifyUrl() != null && !properties.authorizationNotifyUrl().isBlank()) {
            body.put("authorization_notify_url", properties.authorizationNotifyUrl().trim());
        }
        log.info("wechat transfer authorization request outAuthorizationNo={}", outAuthorizationNo);
        JsonNode resp = v3.post("/v3/fund-app/mch-transfer/user-confirm-authorization", body);
        String state = resp.path("state").asText(null);
        String packageInfo = resp.path("package_info").asText(null);
        return new AuthorizationRequest(outAuthorizationNo, state, packageInfo, resp.toString());
    }

    public record AuthorizationRequest(String outAuthorizationNo, String state,
                                        String packageInfo, String rawResponse) {
        public boolean isWaitingUserConfirm() {
            return AUTH_WAIT.equals(state);
        }
    }

    /**
     * 用户授权后转账。outAuthorizationNo 用商户侧授权单号（与 authorization_id 二选一）。
     *
     * @return 转账应答（state=SUCCESS 为终态）
     */
    public TransferResponse transfer(String outBillNo, String openid, long netCents,
                                     String remark, String outAuthorizationNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", payProperties.appId());
        body.put("out_bill_no", outBillNo);
        body.put("transfer_scene_id", properties.sceneId());
        body.put("transfer_amount", netCents);
        body.put("transfer_remark", remark);
        body.put("out_authorization_no", outAuthorizationNo);
        // 佣金报酬场景固定两条报备（官方 4013774590：info_type 固定值，不可重复）
        List<Map<String, String>> reportInfos = new ArrayList<>();
        reportInfos.add(report("岗位类型", properties.reportJobType()));
        reportInfos.add(report("报酬说明", properties.reportRewardNote()));
        body.put("transfer_scene_report_infos", reportInfos);
        if (properties.userRecvPerception() != null && !properties.userRecvPerception().isBlank()) {
            body.put("user_recv_perception", properties.userRecvPerception().trim());
        }
        // 🔴 金额 >= ¥2000 必须传加密姓名；本实现不支持敏感字段加密 ⇒ fail-closed 拒绝
        if (netCents >= 200_000L) {
            throw new PayoutChannel.PayeeIncompatibleException(
                    "微信转账单笔 ≥ ¥2000 需传加密收款人姓名（本通道尚未接入敏感字段加密），拒绝出款");
        }
        log.info("wechat transfer request outBillNo={}, amountCents={}", outBillNo, netCents);
        WeChatPayV3Client.HttpResult result =
                v3.postWithStatus("/v3/fund-app/mch-transfer/transfer-bills/transfer", body);
        if (!result.is2xx()) {
            classifyError("transfer", outBillNo, result.status(), result.code(), result.message());
        }
        return toResponse(result.body());
    }

    /**
     * 🔴 错误码分类（官方 4014399371）——决定「释放冻结」还是「保持 PAYING 转人工」：
     * <ul>
     *   <li>确定失败（可 rejected）：NOT_ENOUGH / USER_ERROR / ORDER_CLOSED / ALREADY_EXISTS(终态)；</li>
     *   <li>结果不确定（必须 Pending，原单重试/查单）：429 频率 / 5xx / SYSTEM_ERROR / 未知码；
     *       官方反复强调「非 200 不得换单号重试」，换单会重复出款。</li>
     * </ul>
     */
    private void classifyError(String action, String outBillNo, int status, String code, String message) {
        String codeSafe = code == null ? "" : code;
        String msgSafe = message == null ? "" : message;
        log.warn("wechat transfer {} error: outBillNo={}, status={}, code={}, message={}",
                action, outBillNo, status, codeSafe, msgSafe);
        boolean definiteFail = switch (codeSafe) {
            case "NOT_ENOUGH", "USER_ERROR", "ORDER_CLOSED", "ALREADY_EXISTS" -> true;
            default -> false;
        };
        String detail = "微信转账失败（HTTP " + status + "，code=" + codeSafe + "，" + msgSafe + "）";
        if (definiteFail) {
            throw new DefiniteFailure(detail);
        }
        // 429/5xx/SYSTEM_ERROR/未知码 ⇒ 结果不确定，绝不当失败（会释放冻结造成双重支出）
        throw new PayoutChannel.PayoutPendingException(detail, outBillNo);
    }

    /** 确定的渠道业务失败——由通道层转 PayoutResult.rejected（释放冻结）。 */
    public static class DefiniteFailure extends RuntimeException {
        public DefiniteFailure(String message) {
            super(message);
        }
    }

    /** 查单兜底（商户单号）。用于回调丢失 / 状态不确定时确认终态。 */
    public TransferResponse queryByOutBillNo(String outBillNo) {
        String path = "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + outBillNo;
        log.debug("wechat transfer query outBillNo={}", outBillNo);
        JsonNode resp = v3.get(path);
        return toResponse(resp);
    }

    private TransferResponse toResponse(JsonNode resp) {
        String state = resp.path("state").asText(null);
        String billNo = resp.path("transfer_bill_no").asText(null);
        String failReason = resp.path("fail_reason").asText(null);
        return new TransferResponse(state, billNo, failReason, resp.toString());
    }

    private Map<String, String> report(String type, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("info_type", type);
        m.put("info_content", content);
        return m;
    }

    /**
     * 转账应答。success=终态成功；pending=非终态（保持 PAYING）；
     * rejected=确定失败（可释放冻结）；failReason 为渠道失败原因。
     */
    public record TransferResponse(String state, String transferBillNo, String failReason,
                                    String rawResponse) {
        public boolean isSuccess() {
            return "SUCCESS".equals(state);
        }

        public boolean isPending() {
            return state != null && (state.equals("ACCEPTED") || state.equals("PROCESSING")
                    || state.equals("TRANSFERING"));
        }

        public boolean isFail() {
            return "FAIL".equals(state) || "CANCELLED".equals(state) || "CANCELING".equals(state);
        }

        public Optional<String> describeFail() {
            if (isFail()) {
                return Optional.of("微信转账终态=" + state
                        + (failReason != null ? "，原因=" + failReason : ""));
            }
            return Optional.empty();
        }
    }
}
