package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 商户费用台账（V355，2026-10-14）：平台不自动扣款，所有结算线下进行——
 * 日结批只生成 PENDING 记录，线下收款后运营标记 SETTLED；催缴/锁柜基于未结清累计。
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
