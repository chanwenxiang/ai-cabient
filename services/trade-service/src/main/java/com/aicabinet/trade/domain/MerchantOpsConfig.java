package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("merchant_ops_config")
public class MerchantOpsConfig {
    @TableId(type = IdType.INPUT)
    private String merchantId;
    private String stockingType;
    private int stockoutThresholdPct;
    private String tallyMode;
    private Boolean useStockingList;
    private String replenishInputType;
    private Boolean photoStocktake;
    private Boolean photoReplenish;
    private int maxInflightOrders;
    private Instant updatedAt;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getStockingType() { return stockingType; }
    public void setStockingType(String stockingType) { this.stockingType = stockingType; }
    public int getStockoutThresholdPct() { return stockoutThresholdPct; }
    public void setStockoutThresholdPct(int stockoutThresholdPct) { this.stockoutThresholdPct = stockoutThresholdPct; }
    public String getTallyMode() { return tallyMode; }
    public void setTallyMode(String tallyMode) { this.tallyMode = tallyMode; }
    public Boolean getUseStockingList() { return useStockingList; }
    public void setUseStockingList(Boolean useStockingList) { this.useStockingList = useStockingList; }
    public String getReplenishInputType() { return replenishInputType; }
    public void setReplenishInputType(String replenishInputType) { this.replenishInputType = replenishInputType; }
    public Boolean getPhotoStocktake() { return photoStocktake; }
    public void setPhotoStocktake(Boolean photoStocktake) { this.photoStocktake = photoStocktake; }
    public Boolean getPhotoReplenish() { return photoReplenish; }
    public void setPhotoReplenish(Boolean photoReplenish) { this.photoReplenish = photoReplenish; }
    public int getMaxInflightOrders() { return maxInflightOrders; }
    public void setMaxInflightOrders(int maxInflightOrders) { this.maxInflightOrders = maxInflightOrders; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
