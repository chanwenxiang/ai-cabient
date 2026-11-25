package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.payment.WeChatPayClient;
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
 * 订单退款取 order_id，充值退款（order_id 为空，见 V98 FK 约束）从 reason 尾部 “#orderId” 解析
 * ——写入侧（PaymentService.recordRechargeRefundOperation）2026-11-12 起改拼 # 对齐本契约；
 * resolveOutTradeNo 同时兼容存量空格尾巴行（R22-1）。</p>
 */
@Component
public class WeChatRefundReconciler {

    private static final Logger log = LoggerFactory.getLogger(WeChatRefundReconciler.class);
    private static final String PROCESSING_MARKER = "[refund:PROCESSING]";

    private final PaymentOperationMapper paymentOperationMapper;
    private final WeChatPayClient weChatPayClient;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final OrderPaymentService orderPaymentService;
    private final PaymentService paymentService;
    private final BalanceRefundService balanceRefundService;
    private final RefundResumeService refundResumeService;

    @org.springframework.beans.factory.annotation.Autowired
    public WeChatRefundReconciler(PaymentOperationMapper paymentOperationMapper,
                                  WeChatPayClient weChatPayClient,
                                  OpsAlertDispatcher opsAlertDispatcher,
                                  OrderPaymentService orderPaymentService,
                                  PaymentService paymentService,
                                  BalanceRefundService balanceRefundService,
                                  RefundResumeService refundResumeService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.weChatPayClient = weChatPayClient;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.orderPaymentService = orderPaymentService;
        this.paymentService = paymentService;
        this.balanceRefundService = balanceRefundService;
        this.refundResumeService = refundResumeService;
    }

    public WeChatRefundReconciler(PaymentOperationMapper paymentOperationMapper,
                                  WeChatPayClient weChatPayClient,
                                  OpsAlertDispatcher opsAlertDispatcher,
                                  OrderPaymentService orderPaymentService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.weChatPayClient = weChatPayClient;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.orderPaymentService = orderPaymentService;
        this.paymentService = null;
        this.balanceRefundService = null;
        this.refundResumeService = null;
    }

    /** @return 推进的退款笔数 */
    public int advanceProcessingRefunds() {
        if (!isWeChatConfigured()) {
            return 0;
        }
        List<PaymentOperation> pending = paymentOperationMapper.findWechatRefundsAwaitingConfirmation();
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
                String nextReason = op.getReason().replace(PROCESSING_MARKER, "[refund:" + status + "]");
                if ("REFUND_PROCESSING".equals(op.getStatus())) {
                    if ("SUCCESS".equalsIgnoreCase(status)) {
                        if (op.getOrderId() != null) {
                            if (orderPaymentService.confirmProcessingOrderRefund(op)) {
                                advanced++;
                            }
                        } else if (advanceRechargeRefund(op, status, nextReason)) {
                            advanced++;
                        }
                    } else if ("ABNORMAL".equalsIgnoreCase(status)) {
                        if (op.getOrderId() != null) {
                            int changed = paymentOperationMapper.finishProcessingRefund(
                                    op.getOperationId(), op.getReason(), nextReason, "REFUND_FAILED");
                            if (changed > 0) {
                                advanced++;
                            }
                        } else if (advanceRechargeRefund(op, status, nextReason)) {
                            advanced++;
                        }
                    }
                } else if ("COMPLETED".equals(op.getStatus()) && "ABNORMAL".equalsIgnoreCase(status)) {
                    if (refundResumeService != null) {
                        refundResumeService.recordLegacyAbnormalTrace(op);
                    } else {
                        op.setReason(nextReason);
                        paymentOperationMapper.updateById(op);
                    }
                    advanced++;
                } else if ("COMPLETED".equals(op.getStatus()) && "SUCCESS".equalsIgnoreCase(status)
                        && op.getOrderId() == null) {
                    if (advanceRechargeRefund(op, status, nextReason)) {
                        advanced++;
                    }
                } else if ("REFUND_FAILED".equals(op.getStatus()) && "ABNORMAL".equalsIgnoreCase(status)
                        && op.getOrderId() == null) {
                    if (advanceRechargeRefund(op, status, nextReason)) {
                        advanced++;
                    }
                } else if ("COMPLETED".equals(op.getStatus()) && "SUCCESS".equalsIgnoreCase(status)
                        && op.getOrderId() != null) {
                    orderPaymentService.resumeCompletedOrderRefund(op.getIdempotencyKey());
                    advanced++;
                } else {
                    op.setReason(nextReason);
                    paymentOperationMapper.updateById(op);
                    advanced++;
                }
                if ("ABNORMAL".equalsIgnoreCase(status)) {
                    log.error("wechat refund abnormal confirmed opId={} outRefundNo={}",
                            op.getOperationId(), op.getGatewayTradeNo());
                    boolean legacyLocalRefund = "COMPLETED".equals(op.getStatus());
                    opsAlertDispatcher.send(legacyLocalRefund
                                    ? "WECHAT_REFUND_ABNORMAL_LEGACY_LOCAL_REVIEW" : "WECHAT_REFUND_ABNORMAL",
                            "微信退款异常（对账确认）",
                            "opId=" + op.getOperationId() + " outRefundNo=" + op.getGatewayTradeNo()
                                    + (legacyLocalRefund ? " 存量本地已退，须人工核对券/分账/订单冲正" : " 本地未记已退"));
                } else {
                    log.info("wechat refund advanced opId={} status={}", op.getOperationId(), status);
                }
            } catch (Exception e) {
                log.warn("wechat refund query failed opId={}: {}", op.getOperationId(), e.getMessage());
            }
        }
        return advanced;
    }

    private boolean advanceRechargeRefund(PaymentOperation op, String status, String nextReason) {
        if (paymentService == null || !paymentService.confirmRechargeChannelRefund(
                resolveOutTradeNo(op), op, status, nextReason)) {
            return false;
        }
        boolean success = "SUCCESS".equalsIgnoreCase(status);
        if (balanceRefundService != null && balanceRefundService.hasChannelSlice(op.getGatewayTradeNo())) {
            if (success) {
                balanceRefundService.confirmChannelSliceSuccess(op.getGatewayTradeNo());
            } else {
                balanceRefundService.failChannelSlice(op.getGatewayTradeNo(), nextReason);
            }
        }
        paymentService.markRechargeRefundContextDone(op);
        return true;
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
        // 充值退款流水 order_id 为空，reason 尾部带充值单号。R22-1（四端审计 2026-11-12）：
        // 写入侧此前拼的是「空格 + 单号」而本类只认 '#' ⇒ 充值 PROCESSING 行永被跳过。
        // 现兼容两种形态：新格式 "#orderId"（2026-11-12 起）与存量空格分隔尾部（纯数字）。
        String reason = op.getReason() == null ? "" : op.getReason().trim();
        int hash = reason.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < reason.length()) {
            String candidate = reason.substring(hash + 1).trim();
            int space = candidate.indexOf(' ');
            String id = space > 0 ? candidate.substring(0, space) : candidate;
            if (!id.isEmpty()) {
                return id;
            }
        }
        // 存量旧格式：空格分隔的最后一个 token（充值单号是纯数字）
        int space = reason.lastIndexOf(' ');
        if (space >= 0 && space + 1 < reason.length()) {
            String tail = reason.substring(space + 1).trim();
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                return tail;
            }
        }
        return null;
    }
}
