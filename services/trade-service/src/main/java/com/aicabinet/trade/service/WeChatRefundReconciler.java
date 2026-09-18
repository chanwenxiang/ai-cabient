package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * H42(c): 对账调度内推进微信 PROCESSING 退款 —— 调 queryRefund 查单，
 * 将 SUCCESS/ABNORMAL 回写进流水 reason 标记；ABNORMAL 额外告警。
 *
 * <p>背景：createRefund 发起后渠道可能返回 PROCESSING，且无退款回调 Controller
 * （无回调地址配置入口），故由每日对账兜底查单推进。
 * out_refund_no 存于流水 gateway_trade_no；out_trade_no：
 * 订单退款取 order_id，充值退款（order_id 为空，见 V98 FK 约束）从 reason 尾部 “#orderId” 解析。</p>
 */
@Component
public class WeChatRefundReconciler {

    private static final Logger log = LoggerFactory.getLogger(WeChatRefundReconciler.class);
    private static final String PROCESSING_MARKER = "[refund:PROCESSING]";

    private final PaymentOperationMapper paymentOperationMapper;
    private final WeChatPayClient weChatPayClient;
    private final OpsAlertDispatcher opsAlertDispatcher;

    public WeChatRefundReconciler(PaymentOperationMapper paymentOperationMapper,
                                  WeChatPayClient weChatPayClient,
                                  OpsAlertDispatcher opsAlertDispatcher) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.weChatPayClient = weChatPayClient;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    /** @return 推进的退款笔数 */
    public int advanceProcessingRefunds() {
        if (!isWeChatConfigured()) {
            return 0;
        }
        List<PaymentOperation> pending = paymentOperationMapper.selectList(
                Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getStatus, "COMPLETED")
                        .eq(PaymentOperation::getChannel, "WECHAT")
                        .like(PaymentOperation::getReason, PROCESSING_MARKER)
                        .isNotNull(PaymentOperation::getGatewayTradeNo)
                        .ne(PaymentOperation::getGatewayTradeNo, "")
                        .last("LIMIT 50"));
        int advanced = 0;
        for (PaymentOperation op : pending) {
            String outTradeNo = resolveOutTradeNo(op);
            if (outTradeNo == null) {
                log.warn("refund reconcile cannot resolve out_trade_no opId={} reason={}",
                        op.getOperationId(), op.getReason());
                continue;
            }
            try {
                JsonNode resp = weChatPayClient.v3().queryRefund(outTradeNo, op.getGatewayTradeNo());
                String status = resp.path("status").asText("");
                if (status.isBlank() || "PROCESSING".equalsIgnoreCase(status)) {
                    continue;
                }
                op.setReason(op.getReason().replace(PROCESSING_MARKER, "[refund:" + status + "]"));
                paymentOperationMapper.updateById(op);
                advanced++;
                if ("ABNORMAL".equalsIgnoreCase(status)) {
                    log.error("wechat refund abnormal confirmed opId={} outRefundNo={}",
                            op.getOperationId(), op.getGatewayTradeNo());
                    opsAlertDispatcher.send("WECHAT_REFUND_ABNORMAL", "微信退款异常（对账确认）",
                            "opId=" + op.getOperationId() + " outRefundNo=" + op.getGatewayTradeNo() + " 需人工介入");
                } else {
                    log.info("wechat refund advanced opId={} status={}", op.getOperationId(), status);
                }
            } catch (Exception e) {
                log.warn("wechat refund query failed opId={}: {}", op.getOperationId(), e.getMessage());
            }
        }
        return advanced;
    }

    private boolean isWeChatConfigured() {
        try {
            return weChatPayClient.v3().properties().isConfigured();
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveOutTradeNo(PaymentOperation op) {
        if (op.getOrderId() != null && !op.getOrderId().isBlank()) {
            return op.getOrderId().trim();
        }
        // 充值退款流水 order_id 为空，reason 以 “#orderId” 结尾（见 recordRechargeRefundOperation）
        String reason = op.getReason() == null ? "" : op.getReason();
        int hash = reason.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < reason.length()) {
            String candidate = reason.substring(hash + 1).trim();
            int space = candidate.indexOf(' ');
            return space > 0 ? candidate.substring(0, space) : candidate;
        }
        return null;
    }
}
