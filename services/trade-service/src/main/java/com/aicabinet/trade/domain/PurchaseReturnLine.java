package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("purchase_return_line")
public class PurchaseReturnLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long returnId;
    private Long purchaseLineId;
    private String skuId;
    private String batchNo;
    private int quantity;

    public Long getLineId() { return lineId; }
    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public Long getPurchaseLineId() { return purchaseLineId; }
    public void setPurchaseLineId(Long purchaseLineId) { this.purchaseLineId = purchaseLineId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
