package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 盘点明细。状态：PENDING（未盘）/ MATCHED（账实相符）/ DIFF（有差异）/ ADJUSTED（已调整）。
 */
@TableName("warehouse_stocktake_line")
public class WarehouseStocktakeLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long stocktakeId;
    private String skuId;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private int bookQty;
    private Integer countedQty;
    private int diffQty;
    private String status = "PENDING";
    private String notes;
    private Instant adjustedAt;

    public Long getLineId() { return lineId; }
    public void setLineId(Long lineId) { this.lineId = lineId; }
    public Long getStocktakeId() { return stocktakeId; }
    public void setStocktakeId(Long stocktakeId) { this.stocktakeId = stocktakeId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getBookQty() { return bookQty; }
    public void setBookQty(int bookQty) { this.bookQty = bookQty; }
    public Integer getCountedQty() { return countedQty; }
    public void setCountedQty(Integer countedQty) { this.countedQty = countedQty; }
    public int getDiffQty() { return diffQty; }
    public void setDiffQty(int diffQty) { this.diffQty = diffQty; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getAdjustedAt() { return adjustedAt; }
    public void setAdjustedAt(Instant adjustedAt) { this.adjustedAt = adjustedAt; }
}
