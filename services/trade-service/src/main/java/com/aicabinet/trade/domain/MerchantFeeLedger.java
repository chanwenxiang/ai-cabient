package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 商户费用台账（V355，2026-10-14；2026-10-16 F-2 拍板改口径）：
 * 日结批<b>先从商户钱包扣款成功、再写行</b>（行即收据，对齐老系统「没扣到钱就不留费用行」），
 * 写入即为 SETTLED；PENDING 只用于人工补录的历史/例外行。
 * 费用为 0 或未配置的柜机不进扣款路径、也不写行。催缴/锁柜只看未结清（PENDING）累计与其账龄。
 */
@TableName("merchant_fee_ledger")
public class MerchantFeeLedger {
    @TableId(type = IdType.AUTO)
    private Long ledgerId;
    private String merchantId;
    private LocalDate feeDate;
    /** REN=租金 / DATA=流量费（费用项可扩展） */
    private String feeType;
    private Long amountCents;
    /** PENDING=待结清 / SETTLED=线下已结清 */
    private String status;
    private Instant settledAt;
    private String remark;

    public Long getLedgerId() { return ledgerId; }
    public void setLedgerId(Long v) { this.ledgerId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public LocalDate getFeeDate() { return feeDate; }
    public void setFeeDate(LocalDate v) { this.feeDate = v; }
    public String getFeeType() { return feeType; }
    public void setFeeType(String v) { this.feeType = v; }
    public Long getAmountCents() { return amountCents; }
    public void setAmountCents(Long v) { this.amountCents = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public String getRemark() { return remark; }
    public void setRemark(String v) { this.remark = v; }
}
