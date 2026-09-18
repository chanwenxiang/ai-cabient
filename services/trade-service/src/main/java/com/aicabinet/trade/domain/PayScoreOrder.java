package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 微信支付分订单（表结构见 V74__payscore_payment.sql）。
 * H64: charge 成功后落一行留痕；会话取消时未支付单置 CANCELLED、
 * 已支付单置 REFUND_REQUIRED 并告警（真实渠道释放/退款 API 未接入）。
 */
@TableName("payscore_order")
@Getter
@Setter
public class PayScoreOrder {

    @TableId(type = IdType.INPUT)
    private String payscoreOrderId;

    private String orderId;

    private Long userId;

    private String openId;

    /** 签约协议标识（gateway 代扣场景为合同号；无微信 service_id 概念，见订单落库处注释）。 */
    private String serviceId;

    private String outOrderNo;

    private Instant serviceStartTime;

    private Instant serviceEndTime;

    private int totalAmountCents;

    private Integer actualAmountCents;

    /** CREATED / DONE / CANCELLED / REFUND_REQUIRED */
    private String orderState;

    private Boolean needUserConfirm;

    private Boolean needCollection;

    private String collectionId;

    private Instant createdAt;

    private Instant updatedAt;
}
