package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.WechatPayoutAuthResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.WeChatPayoutAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信免确认收款授权入口（MA-407，消费者端）。
 *
 * <p>前端流程：用户点「开通免密收款」→ 调本接口拿 {@code packageInfo} →
 * 用微信 JSAPI（wx.openBusinessView）拉起授权页 → 用户点同意 → 授权长期生效。
 *
 * <p>🔴 openid 从登录态反查，不接受前端传参（防串号）。
 */
@RestController
@RequestMapping("/api/v2/payout/wechat")
public class WechatPayoutAuthorizationController {

    private final WeChatPayoutAuthorizationService authorizationService;
    private final WeChatPayProperties wxPayProperties;
    private final AdminAuditService auditService;

    public WechatPayoutAuthorizationController(WeChatPayoutAuthorizationService authorizationService,
                                               WeChatPayProperties wxPayProperties,
                                               AdminAuditService auditService) {
        this.authorizationService = authorizationService;
        this.wxPayProperties = wxPayProperties;
        this.auditService = auditService;
    }

    /**
     * 发起免确认收款授权（返回 package_info 供前端拉起微信授权页）。
     * authz: self-service —— 登录用户只能给自己的 openid 申请授权（userId 来自 AuthInterceptor，
     * openid 由 userId 反查 user_info，前端传参拿不到他人 openid），不涉及运营/他人数据。
     */
    @PostMapping("/authorization")
    public ApiResponse<WechatPayoutAuthResponse> requestAuthorization(
            HttpServletRequest request,
            @RequestBody(required = false) AuthorizationRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String displayName = body == null ? null : body.displayName();
        var result = authorizationService.requestAuthorization(userId, displayName);
        // 资金相关授权必须留痕：授权单号长期有效，是「用户同意我们代打款」的唯一凭证。
        auditService.appendLog(userId, "WECHAT_PAYOUT_AUTHORIZE", "PAYOUT_AUTHORIZATION",
                result.outAuthorizationNo(),
                "开通微信免确认收款授权 state=" + result.state());
        return ApiResponse.ok(new WechatPayoutAuthResponse(
                result.outAuthorizationNo(), result.packageInfo(), result.state(),
                wxPayProperties.mchId()));
    }

    public record AuthorizationRequest(String displayName) {}
}
