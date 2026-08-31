package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
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

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

}
