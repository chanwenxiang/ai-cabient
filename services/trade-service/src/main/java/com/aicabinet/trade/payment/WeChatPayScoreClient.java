package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信支付分（直连·免确认模式）HTTP 客户端——契约出处 CB-064（docs/COMPETITOR_BENCHMARK.md）：
 * <ul>
 *   <li>商户预授权（签约）：{@code POST /v3/payscore/permissions}，返回 {@code apply_permissions_token}
 *       供小程序 {@code wx.openBusinessView} 拉起授权页；用户确认后微信回调
 *       {@code authorization_code}（激活凭证）。</li>
 *   <li>创建支付分订单：{@code POST /v3/payscore/serviceorder}，免确认模式
 *       {@code need_user_confirm} 固定 false，风险金 {@code risk_fund.name=ESTIMATE_ORDER_COST}；
 *       创单失败=微信侧评估不通过（分数门槛/风险金都在微信侧，本代码不判分）。</li>
 *   <li>完结订单：{@code POST /v3/payscore/serviceorder/{out_order_no}/complete}——
 *       {@code post_payments} 逐件「商品信息」（CB-063 结论 3：与开门前价目同源同价）；
 *       完结后 {@code collection.state=USER_PAYING}，扣款与自动催收由微信持续执行。</li>
 *   <li>取消 / 查询：待支付（USER_PAYING）阶段金额只可下调、可取消或同步外部支付。</li>
 * </ul>
 * 准入现实（CB-064）：直连免确认模式 2026-06 起处于灰度、未全量开放——本客户端在其可用前
 * 只被 {@code aicabinet.payscore.enabled + service-id} 门控调用，未配置一律 fail-closed。
 */
@Component
public class WeChatPayScoreClient {

    /** 微信支付分回调事件里「授权状态」取值的容错匹配（以实际回包为准，未知值记台账不拒绝）。 */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .withZone(ZoneOffset.ofHours(8));

    private final WeChatPayV3Client v3Client;
    private final WeChatPayProperties weChatPayProperties;
    private final PayScoreProperties payScoreProperties;

    public WeChatPayScoreClient(WeChatPayV3Client v3Client,
                                WeChatPayProperties weChatPayProperties,
                                PayScoreProperties payScoreProperties) {
        this.v3Client = v3Client;
        this.weChatPayProperties = weChatPayProperties;
        this.payScoreProperties = payScoreProperties;
    }

    /** 支付分契约是否可调（微信支付已配置 + 服务 ID 已下发）。 */
    public boolean isContractReady() {
        return weChatPayProperties.isConfigured() && payScoreProperties.hasServiceId();
    }

    /** 商户预授权（签约）：返回 {@code apply_permissions_token}。 */
    public JsonNode createPermission(String outRequestNo, String openid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("service_id", payScoreProperties.serviceId());
        body.put("out_request_no", outRequestNo);
        body.put("notify_url", payScoreProperties.permissionNotifyUrl());
        if (openid != null && !openid.isBlank()) {
            body.put("openid", openid);
        }
        return v3Client.post("/v3/payscore/permissions", body);
    }

    /** 按商户签约单号查询授权状态（authorization_code / 授权状态）。 */
    public JsonNode queryPermissionByOutRequestNo(String outRequestNo) {
        return v3Client.get("/v3/payscore/permissions/out-request-no/" + outRequestNo
                + "?appid=" + urlEncode(weChatPayProperties.appId())
                + "&service_id=" + urlEncode(payScoreProperties.serviceId()));
    }

    /** 解除授权（用户解约或运营主动解除）。 */
    public JsonNode terminatePermission(String authorizationCode) {
        return v3Client.delete("/v3/payscore/permissions/authorization-code/"
                + urlEncode(authorizationCode));
    }

    /**
     * 创建支付分订单（免确认）。风险金固定传「预估订单费用」；金额取本柜预授权口径
     * （与 {@code checkout.preauth_cents} 同源），完结金额不得超过该值（先免模式）。
     */
    public JsonNode createServiceOrder(String outOrderNo,
                                       String openid,
                                       String serviceIntroduction,
                                       int riskAmountCents,
                                       Instant startTime,
                                       String notifyUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("mchid", weChatPayProperties.mchId());
        body.put("service_id", payScoreProperties.serviceId());
        body.put("out_order_no", outOrderNo);
        body.put("openid", openid);
        body.put("need_user_confirm", false);
        body.put("service_introduction", serviceIntroduction);
        Map<String, Object> riskFund = new LinkedHashMap<>();
        riskFund.put("name", "ESTIMATE_ORDER_COST");
        riskFund.put("amount", riskAmountCents);
        body.put("risk_fund", riskFund);
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("start_time", rfc3339(startTime));
        body.put("time_range", timeRange);
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            body.put("notify_url", notifyUrl);
        }
        return v3Client.post("/v3/payscore/serviceorder", body);
    }

    /**
     * 完结支付分订单（发起扣款）。{@code postPayments} 逐件 {@code {name:"商品信息",
     * amount:单价分, description:SKU 名, count:数量}}——与开门前价目同源同价（CB-063 结论 3）；
     * {@code postDiscounts} 优惠负向单列（官方约束：创单未传则完结可不传）。
     */
    public JsonNode completeServiceOrder(String outOrderNo,
                                         List<Map<String, Object>> postPayments,
                                         List<Map<String, Object>> postDiscounts,
                                         int totalAmountCents,
                                         Instant startTime,
                                         Instant endTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("service_id", payScoreProperties.serviceId());
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("start_time", rfc3339(startTime));
        if (endTime != null) {
            timeRange.put("end_time", rfc3339(endTime));
        }
        body.put("time_range", timeRange);
        body.put("post_payments", postPayments);
        if (postDiscounts != null && !postDiscounts.isEmpty()) {
            body.put("post_discounts", postDiscounts);
        }
        body.put("total_amount", totalAmountCents);
        return v3Client.post("/v3/payscore/serviceorder/" + urlEncode(outOrderNo) + "/complete", body);
    }

    /** 取消支付分订单（仅 CREATED/DOING 等未进入待支付的状态可取消）。 */
    public JsonNode cancelServiceOrder(String outOrderNo, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("service_id", payScoreProperties.serviceId());
        if (reason != null && !reason.isBlank()) {
            body.put("cancel_reason", reason);
        }
        return v3Client.post("/v3/payscore/serviceorder/" + urlEncode(outOrderNo) + "/cancel", body);
    }

    /** 按商户订单号查询支付分订单（对账/回调补偿用）。 */
    public JsonNode queryServiceOrder(String outOrderNo) {
        return v3Client.get("/v3/payscore/serviceorder?appid=" + urlEncode(weChatPayProperties.appId())
                + "&service_id=" + urlEncode(payScoreProperties.serviceId())
                + "&out_order_no=" + urlEncode(outOrderNo));
    }

    private static String rfc3339(Instant instant) {
        return RFC3339.format(instant);
    }

    private static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
