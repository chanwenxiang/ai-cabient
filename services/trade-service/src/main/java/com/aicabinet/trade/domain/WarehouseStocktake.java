package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 整仓盘点单。状态：DRAFT / IN_PROGRESS / COMPLETED / ADJUSTED / CANCELLED。
 * 模式：OPEN=明盘（预填账面数）、BLIND=盲盘（实盘数留空）。
 */
@TableName("warehouse_stocktake")
public class WarehouseStocktake {

    @TableId(type = IdType.AUTO)
    private Long stocktakeId;
    private String stocktakeNo;
    private String warehouseId;
    private String mode = "OPEN";
    private String status = "DRAFT";
    private int bookQty;
    private int countedQty;
    private int diffQty;
    private int diffLineCount;
    private Long operatorId;
    private String notes;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    public Long getStocktakeId() { return stocktakeId; }
    public void setStocktakeId(Long stocktakeId) { this.stocktakeId = stocktakeId; }
    public String getStocktakeNo() { return stocktakeNo; }
    public void setStocktakeNo(String stocktakeNo) { this.stocktakeNo = stocktakeNo; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getBookQty() { return bookQty; }
    public void setBookQty(int bookQty) { this.bookQty = bookQty; }
    public int getCountedQty() { return countedQty; }
    public void setCountedQty(int countedQty) { this.countedQty = countedQty; }
    public int getDiffQty() { return diffQty; }
    public void setDiffQty(int diffQty) { this.diffQty = diffQty; }
    public int getDiffLineCount() { return diffLineCount; }
    public void setDiffLineCount(int diffLineCount) { this.diffLineCount = diffLineCount; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
