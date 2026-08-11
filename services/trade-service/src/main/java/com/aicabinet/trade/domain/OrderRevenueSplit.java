package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("order_revenue_split")
@Getter
@Setter
public class OrderRevenueSplit {

    @TableId(type = IdType.INPUT)
    private String splitId;

    private String orderId;

    private String merchantId;

    private String deviceId;

    private long grossCents;

    private long platformCents;

    private long merchantCents;

    private String status = "ACCRUED";

    private String wechatOutOrderNo;

    private String wechatTransactionId;

    private String failureReason;
    private String settlementBatchNo;
    private LocalDate settleAfter;
    private Instant settledAt;

    private Instant createdAt;

}
