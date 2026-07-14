package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.repository.OrderRevenueSplitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信分账 API v3 骨架。购物订单当前走余额扣款，默认仅记账本（LEDGER_ONLY）；
 * 运营可在绑定微信支付交易号后手动/自动提交分账。
 */
@Service
public class WeChatProfitSharingService {

    private static final Logger log = LoggerFactory.getLogger(WeChatProfitSharingService.class);

    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatPayV3Client weChatPayV3Client;
    private final OrderRevenueSplitRepository splitRepository;

    public WeChatProfitSharingService(ProfitSharingProperties profitSharingProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatPayV3Client weChatPayV3Client,
                                      OrderRevenueSplitRepository splitRepository) {
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatPayV3Client = weChatPayV3Client;
        this.splitRepository = splitRepository;
    }

    public boolean isApiReady() {
        return profitSharingProperties.enabled() && weChatPayProperties.isConfigured();
    }

    @Transactional
    public OrderRevenueSplit submitSplit(OrderRevenueSplit split, Merchant merchant, String wxTransactionId) {
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            throw new IllegalStateException("merchant has no wechatReceiverId");
        }
        if (wxTransactionId == null || wxTransactionId.isBlank()) {
            throw new IllegalStateException("wxTransactionId is required for WeChat profit sharing");
        }
        if (!isApiReady()) {
            throw new IllegalStateException("WeChat profit sharing is not enabled or pay is not configured");
        }
        if (split.getMerchantCents() <= 0) {
            split.setStatus("LEDGER_ONLY");
            split.setFailureReason(null);
            return splitRepository.save(split);
        }
        if ("WECHAT_SUBMITTED".equals(split.getStatus())) {
            return split;
        }

        String outOrderNo = split.getWechatOutOrderNo();
        if (outOrderNo == null || outOrderNo.isBlank()) {
            outOrderNo = "PS" + split.getSplitId();
            split.setWechatOutOrderNo(outOrderNo);
        }

        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("type", "MERCHANT_ID");
        receiver.put("account", merchant.getWechatReceiverId().trim());
        receiver.put("amount", split.getMerchantCents());
        receiver.put("description", "商户分账-" + split.getOrderId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("transaction_id", wxTransactionId.trim());
        body.put("out_order_no", outOrderNo);
        body.put("receivers", List.of(receiver));
        body.put("unfreeze_unsplit", true);

        try {
            JsonNode resp = weChatPayV3Client.post("/v3/profitsharing/orders", body);
            split.setWechatTransactionId(wxTransactionId.trim());
            split.setStatus("WECHAT_SUBMITTED");
            split.setFailureReason(null);
            log.info("wechat profit sharing submitted splitId={} orderId={} wxOrderId={}",
                    split.getSplitId(), split.getOrderId(), resp.path("order_id").asText());
        } catch (Exception e) {
            split.setStatus("WECHAT_FAILED");
            split.setWechatTransactionId(wxTransactionId.trim());
            split.setFailureReason(truncate(e.getMessage()));
            log.warn("wechat profit sharing failed splitId={}: {}", split.getSplitId(), e.getMessage());
        }
        return splitRepository.save(split);
    }

    /**
     * 查询微信分账单状态并同步本地记录。
     */
    @Transactional
    public OrderRevenueSplit refreshSplitStatus(OrderRevenueSplit split) {
        if (!isApiReady()) {
            throw new IllegalStateException("WeChat profit sharing is not enabled or pay is not configured");
        }
        String wxTxn = split.getWechatTransactionId();
        String outOrderNo = split.getWechatOutOrderNo();
        if (wxTxn == null || wxTxn.isBlank() || outOrderNo == null || outOrderNo.isBlank()) {
            throw new IllegalStateException("split missing wxTransactionId or wechatOutOrderNo");
        }
        if ("LEDGER_ONLY".equals(split.getStatus()) || split.getMerchantCents() <= 0) {
            return split;
        }
        try {
            String encodedOut = URLEncoder.encode(outOrderNo.trim(), StandardCharsets.UTF_8);
            String path = "/v3/profitsharing/orders/" + encodedOut
                    + "?transaction_id=" + URLEncoder.encode(wxTxn.trim(), StandardCharsets.UTF_8);
            JsonNode resp = weChatPayV3Client.get(path);
            String state = resp.path("state").asText("");
            applyRemoteState(split, state, resp.path("fail_reason").asText(null));
            return splitRepository.save(split);
        } catch (Exception e) {
            split.setFailureReason(truncate(e.getMessage()));
            log.warn("wechat profit sharing query failed splitId={}: {}", split.getSplitId(), e.getMessage());
            return splitRepository.save(split);
        }
    }

    @Transactional
    public int retryFailedSplits(List<OrderRevenueSplit> splits, Map<String, Merchant> merchantsById) {
        if (!isApiReady() || splits.isEmpty()) {
            return 0;
        }
        int retried = 0;
        for (OrderRevenueSplit split : splits) {
            Merchant merchant = merchantsById.get(split.getMerchantId());
            if (merchant == null) {
                continue;
            }
            String wxTxn = split.getWechatTransactionId();
            if (wxTxn == null || wxTxn.isBlank()) {
                continue;
            }
            submitSplit(split, merchant, wxTxn);
            retried++;
        }
        return retried;
    }

    private void applyRemoteState(OrderRevenueSplit split, String state, String failReason) {
        if (state == null || state.isBlank()) {
            return;
        }
        switch (state.toUpperCase()) {
            case "FINISHED", "PROCESSING" -> {
                split.setStatus("WECHAT_SUBMITTED");
                split.setFailureReason(null);
            }
            case "CLOSED" -> {
                if (failReason != null && !failReason.isBlank()) {
                    split.setStatus("WECHAT_FAILED");
                    split.setFailureReason(truncate(failReason));
                } else {
                    split.setStatus("WECHAT_SUBMITTED");
                    split.setFailureReason(null);
                }
            }
            default -> log.debug("unknown wechat profit sharing state={} splitId={}", state, split.getSplitId());
        }
    }

    @Transactional
    public void markLedgerOnly(OrderRevenueSplit split, String reason) {
        split.setStatus("LEDGER_ONLY");
        split.setFailureReason(reason != null ? truncate(reason) : null);
        splitRepository.save(split);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
