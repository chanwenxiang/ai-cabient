package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 设备租赁押金台账（CB-034）：每台 RENTAL 柜至多一条；押金是负债不是收入。 */
@TableName("device_rental_deposit")
@Getter
@Setter
public class DeviceRentalDeposit {

    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String EVENT_CHARGE = "CHARGE";
    public static final String EVENT_DEDUCT = "DEDUCT";
    public static final String EVENT_RETURN = "RETURN";

    @TableId(type = IdType.AUTO)
    private Long depositId;
    private String deviceId;
    private String merchantId;
    /** 押金本金（分）。 */
    private Long amountCents;
    /** 剩余可退押金（分）= 本金 - Σ扣款。 */
    private Long balanceCents;
    private String status = STATUS_HELD;
    private Instant settledAt;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** 收取方式：ONLINE=线上（从商户余额真扣，参与自动按月退还）/ OFFLINE=线下收缴登记（存量，不自动退还） */
    private String chargeMode = "ONLINE";
    /** 每月退还额（分）：登记时按柜填写，null/<=0 = 不自动退还（不写死） */
    private Long monthlyRefundCents;
    /** 开始退还月序：自登记月起的第 N 个月开始退（老系统默认 3，登记时可改） */
    private Integer refundStartMonth;
    /** 期满奖励（分）：退完当月一次性发入商户余额（老系统仅高档有，登记时可填，默认 0） */
    private Long expireRewardCents;

    public String getChargeMode() { return chargeMode; }
    public void setChargeMode(String v) { this.chargeMode = v; }
    public Long getMonthlyRefundCents() { return monthlyRefundCents; }
    public void setMonthlyRefundCents(Long v) { this.monthlyRefundCents = v; }
    public Integer getRefundStartMonth() { return refundStartMonth; }
    public void setRefundStartMonth(Integer v) { this.refundStartMonth = v; }
    public Long getExpireRewardCents() { return expireRewardCents; }
    public void setExpireRewardCents(Long v) { this.expireRewardCents = v; }
}
