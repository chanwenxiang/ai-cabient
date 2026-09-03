package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MerchantWithdrawPayoutService {

    private static final Logger log = LoggerFactory.getLogger(MerchantWithdrawPayoutService.class);

    private final MerchantWithdrawProperties properties;
    private final WeChatPayProperties weChatPayProperties;

    public MerchantWithdrawPayoutService(MerchantWithdrawProperties properties,
                                         WeChatPayProperties weChatPayProperties) {
        this.properties = properties;
        this.weChatPayProperties = weChatPayProperties;
    }

    public PayoutResult payout(MerchantWithdrawRequest request, Merchant merchant) {
        long netCents = WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), request.getFeeCents());
        if (properties.mockEnabled()) {
            String ref = "MOCK-MW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            log.info("Mock merchant withdraw payout: requestId={}, merchantId={}, amountCents={}, feeCents={}, netCents={}, ref={}",
                    request.getRequestId(), merchant.getMerchantId(), request.getAmountCents(),
                    request.getFeeCents(), netCents, ref);
            return PayoutResult.success("MOCK", ref, "仅 Mock 成功：未发起真实微信转账");
        }
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "缺少微信收款方，无法打款");
        }
        if (!weChatPayProperties.isConfigured()) {
            return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null, "微信支付未配置，无法发起转账");
        }
        log.warn("WeChat merchant transfer skeleton hit: requestId={}, receiver={}, amountCents={}, feeCents={}, netCents={}",
                request.getRequestId(), merchant.getWechatReceiverId(), request.getAmountCents(),
                request.getFeeCents(), netCents);
        return PayoutResult.failure(CabinetConstants.PAY_CHANNEL_WECHAT, null,
                "微信商户转账接口尚未接入（非 Mock 环境不可打款）");
    }

    /** 运营后台展示打款模式，避免误以为已真实到账。 */
    public java.util.Map<String, Object> modeInfo() {
        boolean mock = properties.mockEnabled();
        boolean wx = weChatPayProperties.isConfigured();
        String note;
        if (mock) {
            note = "当前为 Mock 打款：审核通过后标记成功，不发起真实微信转账";
        } else if (!wx) {
            note = "Mock 已关闭且微信支付未配置：打款会失败";
        } else {
            note = "Mock 已关闭：微信商户转账 API 尚未接入，打款会失败";
        }
        return java.util.Map.of(
                "mockEnabled", mock,
                "wechatConfigured", wx,
                "transferApiReady", false,
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
