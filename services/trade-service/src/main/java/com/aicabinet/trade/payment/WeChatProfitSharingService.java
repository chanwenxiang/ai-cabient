package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
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
import lombok.Getter;
import lombok.Setter;

/**
 * 微信分账 API v3 骨架。购物订单当前走余额扣款，默认仅记账本（LEDGER_ONLY）；
 * 运营可在绑定微信支付交易号后手动/自动提交分账。
 */
@Service
@Getter
@Setter
public class WeChatProfitSharingService {

    private static final Logger log = LoggerFactory.getLogger(WeChatProfitSharingService.class);

    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatPayV3Client weChatPayV3Client;
    private final OrderRevenueSplitMapper splitRepository;

    public WeChatProfitSharingService(ProfitSharingProperties profitSharingProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatPayV3Client weChatPayV3Client,
                                      OrderRevenueSplitMapper splitRepository) {
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatPayV3Client = weChatPayV3Client;
        this.splitRepository = splitRepository;
    }

    public boolean isApiReady() {
        if (!profitSharingProperties.enabled()) {
            return false;
        }
        return profitSharingProperties.mockEnabled() || weChatPayProperties.isConfigured();
    }

    public boolean isMockMode() {
        return profitSharingProperties.enabled() && profitSharingProperties.mockEnabled();
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

        if (profitSharingProperties.mockEnabled()) {
            split.setWechatTransactionId(wxTransactionId.trim());
            split.setStatus("WECHAT_SUBMITTED");
            split.setFailureReason(null);
            log.info("mock profit sharing submitted splitId={} orderId={} wxTxn={}",
                    split.getSplitId(), split.getOrderId(), wxTransactionId.trim());
            return splitRepository.save(split);
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
     * 分账回退提交结果。
     */
    public enum ReturnSubmitOutcome {
        SUCCESS, PROCESSING, FAILED
    }

    /**
     * 分账回退：部分退款/全额退款时从接收方回退已分金额。
     */
    @Transactional
    public ReturnSubmitOutcome returnMerchantShare(OrderRevenueSplit split,
                                                   Merchant merchant,
                                                   long returnCents,
                                                   String outReturnNo,
                                                   String description) {
        if (split == null || merchant == null || returnCents <= 0) {
            return ReturnSubmitOutcome.FAILED;
        }
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            log.warn("profit sharing return skipped: merchant missing wechatReceiverId splitId={}", split.getSplitId());
            return ReturnSubmitOutcome.FAILED;
        }
        String outOrderNo = split.getWechatOutOrderNo();
        if (outOrderNo == null || outOrderNo.isBlank()) {
            outOrderNo = "PS" + split.getSplitId();
        }
        String returnNo = outReturnNo == null || outReturnNo.isBlank()
                ? "PSR" + split.getSplitId()
                : outReturnNo.trim();
        if (profitSharingProperties.mockEnabled()) {
            log.info("mock profit sharing return splitId={} orderId={} amount={} outReturnNo={}",
                    split.getSplitId(), split.getOrderId(), returnCents, returnNo);
            return ReturnSubmitOutcome.SUCCESS;
        }
        if (!isApiReady()) {
            return ReturnSubmitOutcome.FAILED;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_order_no", outOrderNo);
        body.put("out_return_no", returnNo);
        body.put("return_mchid", merchant.getWechatReceiverId().trim());
        body.put("amount", returnCents);
        body.put("description", truncate(description != null ? description : "分账回退"));
        try {
            JsonNode resp = weChatPayV3Client.post("/v3/profitsharing/return-orders", body);
            ReturnSubmitOutcome outcome = mapReturnResult(resp.path("result").asText(""));
            log.info("wechat profit sharing return submitted splitId={} outReturnNo={} amount={} outcome={}",
                    split.getSplitId(), returnNo, returnCents, outcome);
            return outcome;
        } catch (Exception e) {
            log.warn("wechat profit sharing return failed splitId={} outReturnNo={}: {}",
                    split.getSplitId(), returnNo, e.getMessage());
            return ReturnSubmitOutcome.FAILED;
        }
    }

    /**
     * 查询分账回退单状态。
     */
    public ReturnSubmitOutcome queryReturnOutcome(String outReturnNo) {
        if (outReturnNo == null || outReturnNo.isBlank()) {
            return ReturnSubmitOutcome.FAILED;
        }
        if (profitSharingProperties.mockEnabled()) {
            return ReturnSubmitOutcome.SUCCESS;
        }
        if (!isApiReady()) {
            return ReturnSubmitOutcome.FAILED;
        }
        try {
            String encoded = URLEncoder.encode(outReturnNo.trim(), StandardCharsets.UTF_8);
            JsonNode resp = weChatPayV3Client.get("/v3/profitsharing/return-orders/" + encoded);
            return mapReturnResult(resp.path("result").asText(""));
        } catch (Exception e) {
            log.warn("wechat profit sharing return query failed outReturnNo={}: {}", outReturnNo, e.getMessage());
            return ReturnSubmitOutcome.FAILED;
        }
    }

    /**
     * 轮询待确认的分账回退并同步本地 split。
     */
    @Transactional
    public boolean refreshPendingReturn(OrderRevenueSplit split) {
        if (split == null || split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank()) {
            return false;
        }
        ReturnSubmitOutcome outcome = queryReturnOutcome(split.getWechatPendingReturnNo());
        return applyReturnOutcome(split, outcome);
    }

    @Transactional
    public int pollPendingReturns(List<OrderRevenueSplit> splits) {
        if (!isApiReady() || splits == null || splits.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (OrderRevenueSplit split : splits) {
            if (refreshPendingReturn(split)) {
                updated++;
            }
        }
        return updated;
    }

    /**
     * 重试失败的分账回退（failureReason 含「分账回退未成功」且记录了 pending 信息）。
     */
    @Transactional
    public int retryFailedReturns(List<OrderRevenueSplit> splits, Map<String, Merchant> merchantsById) {
        if (!isApiReady() || splits == null || splits.isEmpty()) {
            return 0;
        }
        int retried = 0;
        for (OrderRevenueSplit split : splits) {
            if (split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank()) {
                continue;
            }
            long returnCents = split.getWechatPendingReturnCents() != null ? split.getWechatPendingReturnCents() : 0;
            if (returnCents <= 0) {
                continue;
            }
            Merchant merchant = merchantsById.get(split.getMerchantId());
            if (merchant == null) {
                continue;
            }
            ReturnSubmitOutcome outcome = returnMerchantShare(
                    split,
                    merchant,
                    returnCents,
                    split.getWechatPendingReturnNo(),
                    split.getFailureReason() != null ? split.getFailureReason() : "分账回退重试");
            applyReturnOutcome(split, outcome);
            if (outcome != ReturnSubmitOutcome.FAILED) {
                retried++;
            }
        }
        return retried;
    }

    private boolean applyReturnOutcome(OrderRevenueSplit split, ReturnSubmitOutcome outcome) {
        if (split == null || outcome == null) {
            return false;
        }
        switch (outcome) {
            case SUCCESS -> {
                split.setWechatPendingReturnNo(null);
                split.setWechatPendingReturnCents(null);
                split.setFailureReason(null);
                splitRepository.save(split);
                log.info("profit sharing return confirmed splitId={} order={}", split.getSplitId(), split.getOrderId());
                return true;
            }
            case PROCESSING -> {
                split.setFailureReason(null);
                splitRepository.save(split);
                return false;
            }
            case FAILED -> {
                if (split.getFailureReason() == null || !split.getFailureReason().contains("分账回退未成功")) {
                    split.setFailureReason("分账回退未成功需人工处理");
                }
                splitRepository.save(split);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static ReturnSubmitOutcome mapReturnResult(String result) {
        if (result == null || result.isBlank()) {
            return ReturnSubmitOutcome.PROCESSING;
        }
        return switch (result.toUpperCase()) {
            case "SUCCESS" -> ReturnSubmitOutcome.SUCCESS;
            case "FAIL", "FAILED" -> ReturnSubmitOutcome.FAILED;
            default -> ReturnSubmitOutcome.PROCESSING;
        };
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
        if (profitSharingProperties.mockEnabled()) {
            applyRemoteState(split, "FINISHED", null);
            log.info("mock profit sharing refreshed splitId={} status={}", split.getSplitId(), split.getStatus());
            return splitRepository.save(split);
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
