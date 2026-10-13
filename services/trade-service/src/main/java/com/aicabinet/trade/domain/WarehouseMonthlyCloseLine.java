package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 仓库月结行（V327；V348 账期制扩展）。数量列照 {@code WarehouseMonthlyCloseLineDto}
 * （应有=上期+采购入库+调入−退货−调出−上柜；损耗四列单独展示不进应有）。
 *
 * <p>V348 新增：四损耗列（rock/expired/guestWater/feeDeduct，来源见
 * {@code WarehouseMonthlyCloseService} 归集注释）、单价快照 {@code unitPriceCents}
 * （= 目录采购成本，草稿生成时落库）、人工编辑标记 {@code editedFlag}（草稿行编辑后
 * 漂移核对跳过）、行备注 {@code lineRemark}（补录说明）。
 *
 * <p>🔴 <b>差异处置状态机（CB-017 两步法）</b>：
 * <ul>
 *   <li>{@code PENDING} 待认定（盘亏行默认；盘盈行落 {@code SURPLUS}）</li>
 *   <li>{@code CLAIM} 进索赔台账——审批后处置才写 {@code inventory_write_off}
 *       （DRAFT 数据可能重生成，提前挂索赔会成孤儿记录），回填 {@code claimWriteOffId}</li>
 *   <li>{@code NORMAL_LOSS} 正常损耗——金额供财务线下入账（系统无总账模块，不造凭证）</li>
 *   <li>{@code SURPLUS} 盘盈待查</li>
 * </ul>
 * 金额口径：{@code |gap| × SkuCatalog.purchaseCostCents}（静态目录价；成本未配置 → 金额列留空并提示）。
 * 移动加权为二期（无成本流水基础，CB-017 局限已注明）。
 */
@TableName("warehouse_monthly_close_line")
public class WarehouseMonthlyCloseLine {

    public static final String DISP_PENDING = "PENDING";
    public static final String DISP_CLAIM = "CLAIM";
    public static final String DISP_NORMAL_LOSS = "NORMAL_LOSS";
    public static final String DISP_SURPLUS = "SURPLUS";

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long closeId;
    private String skuId;
    private String skuName;

    private int openingQty;
    private int purchaseInQty;
    private int transferInQty;
    private int transferOutQty;
    private int restockQty;
    private int returnQty;
    private int lossQty;
    private int expectedQty;

    private Integer countedQty;
    private Integer gapQty;
    private Long gapAmountCents;
    private String gapDisposition;
    private Long claimWriteOffId;

    private int rockLossQty;
    private int expiredLossQty;
    private int guestWaterQty;
    private int feeDeductQty;
    private Long unitPriceCents;
    private boolean editedFlag;
    private String lineRemark;

    private Instant createdAt;

    public Long getLineId() { return lineId; }
    public void setLineId(Long lineId) { this.lineId = lineId; }
    public Long getCloseId() { return closeId; }
    public void setCloseId(Long closeId) { this.closeId = closeId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getOpeningQty() { return openingQty; }
    public void setOpeningQty(int openingQty) { this.openingQty = openingQty; }
    public int getPurchaseInQty() { return purchaseInQty; }
    public void setPurchaseInQty(int purchaseInQty) { this.purchaseInQty = purchaseInQty; }
    public int getTransferInQty() { return transferInQty; }
    public void setTransferInQty(int transferInQty) { this.transferInQty = transferInQty; }
    public int getTransferOutQty() { return transferOutQty; }
    public void setTransferOutQty(int transferOutQty) { this.transferOutQty = transferOutQty; }
    public int getRestockQty() { return restockQty; }
    public void setRestockQty(int restockQty) { this.restockQty = restockQty; }
    public int getReturnQty() { return returnQty; }
    public void setReturnQty(int returnQty) { this.returnQty = returnQty; }
    public int getLossQty() { return lossQty; }
    public void setLossQty(int lossQty) { this.lossQty = lossQty; }
    public int getExpectedQty() { return expectedQty; }
    public void setExpectedQty(int expectedQty) { this.expectedQty = expectedQty; }
    public Integer getCountedQty() { return countedQty; }
    public void setCountedQty(Integer countedQty) { this.countedQty = countedQty; }
    public Integer getGapQty() { return gapQty; }
    public void setGapQty(Integer gapQty) { this.gapQty = gapQty; }
    public Long getGapAmountCents() { return gapAmountCents; }
    public void setGapAmountCents(Long gapAmountCents) { this.gapAmountCents = gapAmountCents; }
    public String getGapDisposition() { return gapDisposition; }
    public void setGapDisposition(String gapDisposition) { this.gapDisposition = gapDisposition; }
    public Long getClaimWriteOffId() { return claimWriteOffId; }
    public void setClaimWriteOffId(Long claimWriteOffId) { this.claimWriteOffId = claimWriteOffId; }
    public int getRockLossQty() { return rockLossQty; }
    public void setRockLossQty(int rockLossQty) { this.rockLossQty = rockLossQty; }
    public int getExpiredLossQty() { return expiredLossQty; }
    public void setExpiredLossQty(int expiredLossQty) { this.expiredLossQty = expiredLossQty; }
    public int getGuestWaterQty() { return guestWaterQty; }
    public void setGuestWaterQty(int guestWaterQty) { this.guestWaterQty = guestWaterQty; }
    public int getFeeDeductQty() { return feeDeductQty; }
    public void setFeeDeductQty(int feeDeductQty) { this.feeDeductQty = feeDeductQty; }
    public Long getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(Long unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public boolean isEditedFlag() { return editedFlag; }
    public void setEditedFlag(boolean editedFlag) { this.editedFlag = editedFlag; }
    public String getLineRemark() { return lineRemark; }
    public void setLineRemark(String lineRemark) { this.lineRemark = lineRemark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
