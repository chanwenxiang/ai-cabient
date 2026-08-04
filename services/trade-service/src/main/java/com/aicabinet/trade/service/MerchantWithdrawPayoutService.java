package com.aicabinet.trade.service;

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
        if (properties.mockEnabled()) {
            String ref = "MOCK-MW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            log.info("Mock merchant withdraw payout: requestId={}, merchantId={}, amountCents={}, ref={}",
                    request.getRequestId(), merchant.getMerchantId(), request.getAmountCents(), ref);
            return PayoutResult.success("MOCK", ref, "Mock 打款成功");
        }
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            return PayoutResult.failure("WECHAT", null, "缺少微信收款方，无法打款");
        }
        if (!weChatPayProperties.isConfigured()) {
            return PayoutResult.failure("WECHAT", null, "微信支付未配置，无法发起转账");
        }
        log.warn("WeChat merchant transfer skeleton hit: requestId={}, receiver={}, amountCents={}",
                request.getRequestId(), merchant.getWechatReceiverId(), request.getAmountCents());
        return PayoutResult.failure("WECHAT", null, "微信商户转账接口尚未接入");
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
