package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("balance_refund_request")
@Getter
@Setter
public class BalanceRefundRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;

    private String requestNo;

    private Long userId;

    private int amountCents;

    private String status;

    private String reason;

    private String reviewRemark;

    private Long reviewerId;

    private Instant reviewedAt;

    private String failReason;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant refundedAt;
}
