package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

@TableName("cabinet_order_line")
public class CabinetOrderLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private String orderId;
    private String skuId;

    private String skuName;

    private int quantity;

    private int unitPriceCents;

    private int lineAmountCents;

    private Float confidence;

    private String batchNo;

    private Integer unitCostCents;

    public Long getId() { return id; }
public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(int unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public int getLineAmountCents() { return lineAmountCents; }
    public void setLineAmountCents(int lineAmountCents) { this.lineAmountCents = lineAmountCents; }
    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Integer getUnitCostCents() { return unitCostCents; }
    public void setUnitCostCents(Integer unitCostCents) { this.unitCostCents = unitCostCents; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
}