package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("recharge_order")
@Getter
@Setter
public class RechargeOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;

    private Long userId;

    private int amountCents;

    private String channel;

    private String status;

    private String idempotencyKey;

    private String paymentOperationId;

    private String wxPrepayId;

    private String wxTransactionId;

    private String alipayTradeNo;

    private Instant createdAt;

    private Instant paidAt;
    private Instant refundedAt;

    /** 已原路退回金额（分）；可多次部分退，直至达到 amountCents。 */
    private int refundedCents;

}
