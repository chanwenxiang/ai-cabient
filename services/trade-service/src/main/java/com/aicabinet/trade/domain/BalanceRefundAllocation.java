package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("balance_refund_allocation")
@Getter
@Setter
public class BalanceRefundAllocation {

    @TableId(type = IdType.AUTO)
    private Long allocationId;

    private Long requestId;

    private String rechargeOrderId;

    private int amountCents;

    private String channel;

    private String outRefundNo;

    private Instant createdAt;
}
