package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 仓库月结单主表（V327，E7 缺口 #9；竞品口径见 {@code docs/COMPETITOR_BENCHMARK.md} CB-017）。
 *
 * <p><b>两步法（行业口径）</b>：差异先落「待处理」，处置时逐行分流
 * （{@code PENDING/CLAIM/NORMAL_LOSS/SURPLUS}，见 {@link WarehouseMonthlyCloseLine}）。
 *
 * <p>🔴 <b>唯一约束 {@code (warehouse_id, year_month)}</b>：一仓库一月一张单。
 * 重生成 = 删 DRAFT 重建；APPROVED 后锁单（不可改删、不可重生成）。
 *
 * <p>🔴 <b>盘亏不生成应付</b>（CB-017 修正 E7 原始假设）：有责任方差异经
 * {@link WarehouseMonthlyCloseLine#getClaimWriteOffId()} 关联 {@code inventory_write_off}
 * 进 E4a 索赔台账；无责任方正常损耗金额供财务线下入账。
 */
@TableName("warehouse_monthly_close")
public class WarehouseMonthlyCloseSheet {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_APPROVED = "APPROVED";

    @TableId(type = IdType.AUTO)
    private Long closeId;

    private String warehouseId;
    private String yearMonth;
    private String status;
    private int lossQty;
    private int surplusQty;
    private long lossAmountCents;
    private long surplusAmountCents;
    private int lineCount;

    private Long approvedBy;
    private String approvedByName;
    private Instant approvedAt;

    private String remark;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getCloseId() { return closeId; }
    public void setCloseId(Long closeId) { this.closeId = closeId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getYearMonth() { return yearMonth; }
    public void setYearMonth(String yearMonth) { this.yearMonth = yearMonth; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getLossQty() { return lossQty; }
    public void setLossQty(int lossQty) { this.lossQty = lossQty; }
    public int getSurplusQty() { return surplusQty; }
    public void setSurplusQty(int surplusQty) { this.surplusQty = surplusQty; }
    public long getLossAmountCents() { return lossAmountCents; }
    public void setLossAmountCents(long lossAmountCents) { this.lossAmountCents = lossAmountCents; }
    public long getSurplusAmountCents() { return surplusAmountCents; }
    public void setSurplusAmountCents(long surplusAmountCents) { this.surplusAmountCents = surplusAmountCents; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }
    public String getApprovedByName() { return approvedByName; }
    public void setApprovedByName(String approvedByName) { this.approvedByName = approvedByName; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
