package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payout.WeChatPayoutChannel;
import com.aicabinet.trade.payout.WeChatTransferClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 微信免确认收款授权编排（MA-407）。
 *
 * <p>🔴 <b>openid 只从登录态反查，绝不信前端传参</b>——否则任意用户可给他人绑授权单号，
 * 造成收款账户串号。 userId → user_info.wx_open_id 是唯一可信来源。
 */
@Service
public class WeChatPayoutAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayoutAuthorizationService.class);

    private final UserInfoMapper userInfoMapper;
    private final WeChatTransferClient transferClient;
    private final WeChatPayoutChannel payoutChannel;

    public WeChatPayoutAuthorizationService(UserInfoMapper userInfoMapper,
                                            WeChatTransferClient transferClient,
                                            WeChatPayoutChannel payoutChannel) {
        this.userInfoMapper = userInfoMapper;
        this.transferClient = transferClient;
        this.payoutChannel = payoutChannel;
    }

    public record AuthorizationResult(String outAuthorizationNo, String packageInfo, String state) {}

    /**
     * 为当前登录用户发起免确认收款授权。
     *
     * @param userId      登录用户 ID（来自 AuthInterceptor，非前端传参）
     * @param displayName 用户展示名（授权页展示「开通账号」，默认用姓名/昵称）
     */
    public AuthorizationResult requestAuthorization(Long userId, String displayName) {
        if (!transferClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信商家转账通道未就绪");
        }
        UserInfo user = userInfoMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        String openid = user.getWxOpenId();
        if (openid == null || openid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前账号未绑定微信 openid（请先用微信登录消费者小程序）");
        }
        String outAuthorizationNo = payoutChannel.authorizationNoFor(openid);
        String name = displayName != null && !displayName.isBlank()
                ? displayName.trim()
                : (user.getNickname() != null && !user.getNickname().isBlank() ? user.getNickname() : "微信用户");
        WeChatTransferClient.AuthorizationRequest request =
                requestWithDiagnostics(openid, name, outAuthorizationNo);
        log.info("wechat payout authorization requested: userId={}, state={}, outAuthorizationNo={}",
                userId, request.state(), outAuthorizationNo);
        return new AuthorizationResult(outAuthorizationNo, request.packageInfo(), request.state());
    }

    /**
     * 微信拒绝授权申请时把错误码带出来（502，而不是 500「服务器内部错误」）。
     * 实测价值：商户未开通「免确认模式」/ appid 未绑定商家转账时，只有错误码能定位。
     */
    private WeChatTransferClient.AuthorizationRequest requestWithDiagnostics(
            String openid, String displayName, String outAuthorizationNo) {
        try {
            return transferClient.requestAuthorization(openid, displayName, outAuthorizationNo);
        } catch (WeChatTransferClient.AuthorizationRejected e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "微信免确认收款开通失败（" + e.code() + "），请联系客服核对商户配置", e);
        }
    }
}
