package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 外部渠道流水（CB-036，V347）：批次确认后的聚合事实表（仓×SKU×渠道×操作类型）。
 *
 * <p>🔴 月结（账期制）取数源：按 {@code period_label + warehouse_id + op_type} 归集——
 * {@code WAREHOUSE_OUT} → 月结「仓库外调」列；{@code GUEST_WATER} → 「客情用水」损耗列；
 * {@code WAREHOUSE_STOCKTAKE} → 盘点实盘参考。本表不回写 {@code warehouse_movement}
 * （外部渠道业务时间在往期，混入实时流水会污染实时库存口径）。
 */
@TableName("external_channel_flow")
public class ExternalChannelFlow {

    @TableId(type = IdType.AUTO)
    private Long flowId;

    private Long batchId;
    private String channelCode;
    private String periodLabel;
    private String warehouseId;
    private String personName;
    private String skuId;
    private String opType;
    private int qty;
    private Long unitPriceCents;
    private LocalDate happenedMin;
    private LocalDate happenedMax;
    private int rowCount;
    private String remark;
    private Instant createdAt;

    public Long getFlowId() { return flowId; }
    public void setFlowId(Long flowId) { this.flowId = flowId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getPersonName() { return personName; }
    public void setPersonName(String personName) { this.personName = personName; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getOpType() { return opType; }
    public void setOpType(String opType) { this.opType = opType; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public Long getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(Long unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public LocalDate getHappenedMin() { return happenedMin; }
    public void setHappenedMin(LocalDate happenedMin) { this.happenedMin = happenedMin; }
    public LocalDate getHappenedMax() { return happenedMax; }
    public void setHappenedMax(LocalDate happenedMax) { this.happenedMax = happenedMax; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
