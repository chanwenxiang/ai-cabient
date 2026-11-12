package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** 存量兼容入口：没有腿列表时按 split 单值兜底，仅用于告警文案，绝不用于资金动作。 */
    public void sendCompensationExhausted(CompensationTask task, OrderRevenueSplit split) {
        sendCompensationExhausted(task, split, List.of());
    }

    /**
     * R20-9 / CB-085：告警金额按未完结腿累加，并把每条腿的单号都列出来。
     * 改前只取 split 上那对被覆盖过的单值列，多轮部分退时告警金额会少报。
     */
    public void sendCompensationExhausted(CompensationTask task, OrderRevenueSplit split,
                                          List<OrderSplitReturnLeg> openLegs) {
        if (split == null) {
            return;
        }
        List<OrderSplitReturnLeg> legs = openLegs == null ? List.of() : openLegs.stream()
                .filter(l -> l != null && ProfitSharingReturnLegService.isOpen(l.getStatus()))
                .toList();
        long pendingCents = legs.isEmpty()
                ? (split.getWechatPendingReturnCents() != null ? split.getWechatPendingReturnCents() : 0L)
                : legs.stream().mapToLong(OrderSplitReturnLeg::getCents).sum();
        String outReturnNos = legs.isEmpty()
                ? (split.getWechatPendingReturnNo() != null ? split.getWechatPendingReturnNo() : "")
                : legs.stream().map(OrderSplitReturnLeg::getOutReturnNo)
                        .filter(n -> n != null && !n.isBlank())
                        .collect(Collectors.joining(","));
        String title = "[分账回退补偿失败]";
        String msg = String.format(
                "补偿任务 %s 已达最大重试 splitId=%s orderId=%s merchantId=%s 待回退=%d分 未完结腿数=%d outReturnNo=%s failure=%s",
                task.getTaskId(),
                split.getSplitId(),
                split.getOrderId(),
                split.getMerchantId(),
                pendingCents,
                legs.size(),
                outReturnNos,
                split.getFailureReason() != null ? split.getFailureReason() : "");
        log.error(LITERAL, title, msg);
        Map<String, Object> extra = Map.of(
                SPLITID, split.getSplitId() != null ? split.getSplitId() : "",
                ORDERID, split.getOrderId() != null ? split.getOrderId() : "",
                MERCHANTID, split.getMerchantId() != null ? split.getMerchantId() : "",
                "taskId", task.getTaskId() != null ? task.getTaskId() : 0L,
                "retryCount", task.getRetryCount(),
                "pendingReturnCents", pendingCents,
                "openLegCount", legs.size(),
                "outReturnNo", outReturnNos
        );
        opsExceptionService.report(
                SPLIT_EXCEPTION, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(split.getDeviceId(), null, split.getOrderId(), null), title, msg + " taskId=" + task.getTaskId());
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
                SPLIT_EXCEPTION, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(split.getDeviceId(), null, split.getOrderId(), null), title, msg);
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
                SPLIT_EXCEPTION, "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(split.getDeviceId(), null, split.getOrderId(), null), title, msg);
        opsAlertDispatcher.send("PROFIT_SHARING_RETURN_SUBMIT_FAILED", title, msg, extra);
    }
}
