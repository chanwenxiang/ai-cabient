package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LineWithdrawPayoutService {

    private static final Logger log = LoggerFactory.getLogger(LineWithdrawPayoutService.class);

    private final LineWithdrawProperties properties;
    private final WeChatPayProperties weChatPayProperties;

    public LineWithdrawPayoutService(LineWithdrawProperties properties, WeChatPayProperties weChatPayProperties) {
        this.properties = properties;
        this.weChatPayProperties = weChatPayProperties;
    }

    public PayoutResult payout(LineWithdrawRequest request, LineManager manager) {
        if (properties.mockEnabled()) {
            String ref = "MOCK-LW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            log.info("Mock line withdraw payout: requestId={}, managerId={}, amountCents={}, ref={}",
                    request.getRequestId(), manager.getManagerId(), request.getAmountCents(), ref);
            return PayoutResult.success("MOCK", ref, "Mock 打款成功");
        }
        if (manager.getWxOpenid() == null || manager.getWxOpenid().isBlank()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "缺少 wx_openid，无法打款到零钱");
        }
        if (!weChatPayProperties.isConfigured()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "微信支付未配置，无法发起转账");
        }
        // 骨架：生产需对接商家转账到零钱 API（/v3/transfer/batches 或新版单笔接口）
        log.warn("WeChat transfer skeleton hit: requestId={}, openid={}, amountCents={}",
                request.getRequestId(), manager.getWxOpenid(), request.getAmountCents());
        return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "微信转账接口尚未接入");
    }

    public record PayoutResult(
            boolean success,
            String payChannel,
            String payoutRef,
            String message
    ) {
        static PayoutResult success(String channel, String ref, String message) {
            return new PayoutResult(true, channel, ref, message);
        }

        static PayoutResult failure(String channel, String ref, String message) {
            return new PayoutResult(false, channel, ref, message);
        }
    }
}
