package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 退款流水（B3 专项，V485）：逐笔记录退款发生时间/金额/执行方式。
 *
 * <p>口径分工：{@code cabinet_order.refunded_cents} 保持**订单级聚合**（资金对账真值，
 * MA-284 判据与 MA-270 巡检都读它）；本表只负责**逐笔明细**——月账单退款按流水分日
 * （MA-271 精度解）、渠道重试按 refund_no 幂等、finalize 半段丢失可回放（MA-269 残留解）。
 * 两侧一致性由 DataConsistency 的 REFUND_FLOW_VS_ORDER_MISMATCH 巡检兜底。</p>
 *
 * <p>⚠️ 本表**不是**资金真值：新增退款路径时必须先推进 refunded_cents 再记流水（与现埋点
 * 同序），漏记流水只会让月账单退款显示变少（可见、可巡检），不会多扣钱。</p>
 */
@Data
@TableName("refund_flow")
public class RefundFlow {

    @TableId(type = IdType.AUTO)
    private Long flowId;

    private String orderId;

    /** 从订单复制（月账单按商户过滤用）。 */
    private String merchantId;

    /** 订单原收款渠道（月账单 c1/c2 分桶口径：非 BALANCE vs BALANCE）。 */
    private String orderPayChannel;

    private Integer amountCents;

    /** 退款执行方式（{@link RefundFlowService} METHOD_* 常量）。 */
    private String refundMethod;

    /** 渠道退款单号（outRefundNo）；唯一索引做幂等。本地退款无单号 ⇒ NULL。 */
    private String refundNo;

    private Instant refundedAt;

    private String reason;

    private Instant createdAt;
}
