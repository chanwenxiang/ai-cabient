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
        long netCents = WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), request.getFeeCents());
        if (properties.mockEnabled()) {
            String ref = "MOCK-LW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            log.info("Mock line withdraw payout: requestId={}, managerId={}, amountCents={}, feeCents={}, netCents={}, ref={}",
                    request.getRequestId(), manager.getManagerId(), request.getAmountCents(),
                    request.getFeeCents(), netCents, ref);
            return PayoutResult.success("MOCK", ref, "仅 Mock 成功：未发起真实微信转账");
        }
        if (manager.getWxOpenid() == null || manager.getWxOpenid().isBlank()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "缺少 wx_openid，无法打款到零钱");
        }
        if (!weChatPayProperties.isConfigured()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "微信支付未配置，无法发起转账");
        }
        // 骨架：生产需对接商家转账到零钱 API（/v3/transfer/batches 或新版单笔接口）
        log.warn("WeChat transfer skeleton hit: requestId={}, openid={}, amountCents={}, feeCents={}, netCents={}",
                request.getRequestId(), manager.getWxOpenid(), request.getAmountCents(),
                request.getFeeCents(), netCents);
        return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null,
                "微信转账到零钱接口尚未接入（非 Mock 环境不可打款）");
    }

    /** 运营后台展示打款模式，避免误以为已真实到账。 */
    public java.util.Map<String, Object> modeInfo() {
        boolean mock = properties.mockEnabled();
        boolean wx = weChatPayProperties.isConfigured();
        long feeCents = properties.feeCents();
        long feeBps = properties.feeBps();
        String feeNote = "；手续费=固定 " + feeCents + " 分 + " + feeBps + " bps（仅新申请写入 feeCents）";
        String note;
        if (mock) {
            note = "当前为 Mock 打款：审核通过后标记成功，不发起真实微信转账到零钱" + feeNote;
        } else if (!wx) {
            note = "Mock 已关闭且微信支付未配置：打款会失败" + feeNote;
        } else {
            note = "Mock 已关闭：微信转账到零钱 API 尚未接入，打款会失败" + feeNote;
        }
        return java.util.Map.of(
                "mockEnabled", mock,
                "wechatConfigured", wx,
                "transferApiReady", false,
                "feeCents", feeCents,
                "feeBps", feeBps,
                "note", note
        );
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
