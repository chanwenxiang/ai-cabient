package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 微信分账运营告警：回退补偿失败、改单增额需人工补分账等。
 */
@Service
public class ProfitSharingReturnAlertService {
    private static final String SPLIT_EXCEPTION = "SPLIT_EXCEPTION";
    private static final String MERCHANTID = "merchantId";
    private static final String ORDERID = "orderId";
    private static final String SPLITID = "splitId";
    private static final String LITERAL = "{} {}";


    private static final Logger log = LoggerFactory.getLogger(ProfitSharingReturnAlertService.class);

    private final OpsAlertDispatcher opsAlertDispatcher;
    private final OpsExceptionService opsExceptionService;

    public ProfitSharingReturnAlertService(OpsAlertDispatcher opsAlertDispatcher,
                                           OpsExceptionService opsExceptionService) {
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.opsExceptionService = opsExceptionService;
    }

    public void sendCompensationExhausted(CompensationTask task, OrderRevenueSplit split) {
        if (split == null) {
            return;
        }
        String title = "[分账回退补偿失败]";
        long pendingCents = split.getWechatPendingReturnCents() != null ? split.getWechatPendingReturnCents() : 0L;
        String msg = String.format(
                "补偿任务 #%s 已达最大重试 splitId=%s orderId=%s merchantId=%s 待回退=%d分 outReturnNo=%s failure=%s",
                task.getTaskId(),
                split.getSplitId(),
                split.getOrderId(),
                split.getMerchantId(),
                pendingCents,
                split.getWechatPendingReturnNo(),
                split.getFailureReason() != null ? split.getFailureReason() : "");
        log.error(LITERAL, title, msg);
        Map<String, Object> extra = Map.of(
                SPLITID, split.getSplitId() != null ? split.getSplitId() : "",
                ORDERID, split.getOrderId() != null ? split.getOrderId() : "",
                MERCHANTID, split.getMerchantId() != null ? split.getMerchantId() : "",
                "taskId", task.getTaskId() != null ? task.getTaskId() : 0L,
                "retryCount", task.getRetryCount(),
                "pendingReturnCents", pendingCents,
                "outReturnNo", split.getWechatPendingReturnNo() != null ? split.getWechatPendingReturnNo() : ""
        );
        opsExceptionService.report(
                SPLIT_EXCEPTION,
                "HIGH",
                split.getDeviceId(),
                null,
                split.getOrderId(),
                null,
                title,
                msg + " taskId=" + task.getTaskId());
        // 运营异常中心按 orderId 去重；Webhook 仍可能重复投递，属可接受告警冗余
        opsAlertDispatcher.send("PROFIT_SHARING_RETURN_FAILED", title, msg, extra);
    }

    /** 已提交微信分账后改单增额：本地已重算，需运营人工向微信补分账差额。 */
    public void sendManualSupplementRequired(OrderRevenueSplit split,
                                             long oldMerchantCents,
                                             long newMerchantCents) {
        if (split == null) {
            return;
        }
        long delta = Math.max(0, newMerchantCents - oldMerchantCents);
        if (delta <= 0) {
            return;
        }
        String title = "[分账需人工补分账]";
        String msg = String.format(
                "订单 %s splitId=%s merchantId=%s 改单增额：商户分账 %d→%d 分（差额 %d 分），微信侧需人工补提交",
                split.getOrderId(),
                split.getSplitId(),
                split.getMerchantId(),
                oldMerchantCents,
                newMerchantCents,
                delta);
        log.warn(LITERAL, title, msg);
        Map<String, Object> extra = Map.of(
                SPLITID, split.getSplitId() != null ? split.getSplitId() : "",
                ORDERID, split.getOrderId() != null ? split.getOrderId() : "",
                MERCHANTID, split.getMerchantId() != null ? split.getMerchantId() : "",
                "oldMerchantCents", oldMerchantCents,
                "newMerchantCents", newMerchantCents,
                "deltaCents", delta,
                "splitStatus", split.getStatus() != null ? split.getStatus() : ""
        );
        opsExceptionService.report(
                SPLIT_EXCEPTION,
                "HIGH",
                split.getDeviceId(),
                null,
                split.getOrderId(),
                null,
                title,
                msg);
        opsAlertDispatcher.send("PROFIT_SHARING_MANUAL_SUPPLEMENT", title, msg, extra);
    }

    /** 微信分账回退提交失败：立即告警，补偿任务将自动重试。 */
    public void sendReturnSubmitFailed(OrderRevenueSplit split,
                                       String outReturnNo,
                                       long returnCents,
                                       String failureMessage) {
        if (split == null || returnCents <= 0) {
            return;
        }
        String title = "[分账回退提交失败]";
        String msg = String.format(
                "订单 %s splitId=%s merchantId=%s 回退 %d 分 outReturnNo=%s：%s（补偿任务将自动重试）",
                split.getOrderId(),
                split.getSplitId(),
                split.getMerchantId(),
                returnCents,
                outReturnNo != null ? outReturnNo : "",
                failureMessage != null ? failureMessage : "");
        log.warn(LITERAL, title, msg);
        Map<String, Object> extra = Map.of(
                SPLITID, split.getSplitId() != null ? split.getSplitId() : "",
                ORDERID, split.getOrderId() != null ? split.getOrderId() : "",
                MERCHANTID, split.getMerchantId() != null ? split.getMerchantId() : "",
                "returnCents", returnCents,
                "outReturnNo", outReturnNo != null ? outReturnNo : "",
                "failureMessage", failureMessage != null ? failureMessage : ""
        );
        opsExceptionService.report(
                SPLIT_EXCEPTION,
                "HIGH",
                split.getDeviceId(),
                null,
                split.getOrderId(),
                null,
                title,
                msg);
        opsAlertDispatcher.send("PROFIT_SHARING_RETURN_SUBMIT_FAILED", title, msg, extra);
    }
}
