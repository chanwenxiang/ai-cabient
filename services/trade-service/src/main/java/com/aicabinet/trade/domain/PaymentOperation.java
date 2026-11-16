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

    /** B1-B2：本次扣款尝试的渠道（WECHAT/ALIPAY/BALANCE），供 UNKNOWN 查单定位。 */
    private String attemptedChannel;

    /** B1-B2：本次渠道实际尝试扣款额（分，净额口径），不是订单总额。 */
    private Integer chargeCents;

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
