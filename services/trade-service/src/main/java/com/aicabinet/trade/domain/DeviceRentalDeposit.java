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
    /** 撤机安装费扣额（分）：结算（撤机）时从押金扣。<b>默认 0=不收</b>（用户 2026-10-17 拍板 + V383；老系统硬编码 100 元、不分柜不分商户） */
    private Long installFeeDeductCents;
    /** 已退完且未满合同年数时撤机，是否向商户余额倒扣安装费（老系统行为）。<b>V376 起默认关</b>：资损面须显式传 true 才开 */
    private Boolean installFeeClawbackEnabled;
    /** 合同年数：撤机时登记满 N 年则发期满奖励、免扣安装费（老系统 5 年，登记可改） */
    private Integer contractYears;

    public String getChargeMode() { return chargeMode; }
    public void setChargeMode(String v) { this.chargeMode = v; }
    public Long getMonthlyRefundCents() { return monthlyRefundCents; }
    public void setMonthlyRefundCents(Long v) { this.monthlyRefundCents = v; }
    public Integer getRefundStartMonth() { return refundStartMonth; }
    public void setRefundStartMonth(Integer v) { this.refundStartMonth = v; }
    public Long getExpireRewardCents() { return expireRewardCents; }
    public void setExpireRewardCents(Long v) { this.expireRewardCents = v; }
    public Long getInstallFeeDeductCents() { return installFeeDeductCents; }
    public void setInstallFeeDeductCents(Long v) { this.installFeeDeductCents = v; }
    public Boolean getInstallFeeClawbackEnabled() { return installFeeClawbackEnabled; }
    public void setInstallFeeClawbackEnabled(Boolean v) { this.installFeeClawbackEnabled = v; }
    public Integer getContractYears() { return contractYears; }
    public void setContractYears(Integer v) { this.contractYears = v; }
}
