package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "payment_platform_bill_line", autoResultMap = true)
@Getter
@Setter
public class PaymentPlatformBillLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long reconId;

    private String channel;

    private String platformTradeNo;

    private String merchantOrderNo;

    private long amountCents;

    /** CB-020③：渠道实收手续费（分）；null=该通道账单不提供手续费（历史行/Mock/支付宝未映射）。 */
    private Long feeCents;

    private Instant tradeTime;

    private String tradeType;

    private boolean matched;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String rawDetail;

    private Instant createdAt;

}
