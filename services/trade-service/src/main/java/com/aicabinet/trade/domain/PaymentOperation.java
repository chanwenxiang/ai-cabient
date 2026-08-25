package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("payment_operation")
@Getter
@Setter
public class PaymentOperation {

    @TableId(type = IdType.INPUT)
    private String operationId;

    private String orderId;

    private String operationType;

    private int amountCents;

    private String channel;

    private String status;

    private String idempotencyKey;

    private String gatewayTradeNo;

    private String reason;

    private Long userId;

    private Integer balanceBeforeCents;

    private Integer balanceAfterCents;

    private Instant createdAt;

}
