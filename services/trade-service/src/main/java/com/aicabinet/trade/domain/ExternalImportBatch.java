package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 外部渠道导入批次（CB-036，V347）。
 *
 * <p>状态机：{@code PARSED}（解析完成待确认，可删除）→ {@code COMMITTED}（已聚合入流水，锁死）。
 * 解析行全量 {@code parsedRows}（JSONB，统一 9 列）留痕；确认后聚合为
 * {@link ExternalChannelFlow}（仓×SKU×渠道×操作类型）。
 */
@TableName("external_import_batch")
public class ExternalImportBatch {

    public static final String STATUS_PARSED = "PARSED";
    public static final String STATUS_COMMITTED = "COMMITTED";

    @TableId(type = IdType.AUTO)
    private Long batchId;

    private String channelCode;
    private String periodLabel;
    private String fileName;
    /** MANUAL 渠道导入时人工指定的线长（嗨便利/健怡/无人店） */
    private String personOverride;
    private String status;
    private int rowsTotal;
    private int rowsMapped;
    private int rowsUnmapped;
    /** JSONB：未映射外部商品清单 [{externalKey, externalName, timesSeen}] */
    private String unmappedProducts;
    /** JSONB：无法归仓人员 ["张三", ...] */
    private String unresolvedPersons;
    /** JSONB：统一 9 列解析行 [{person,extKey,extName,qty,unitPriceCents,happenedAt,srcLine}] */
    private String parsedRows;
    private Long uploadedBy;
    private Instant uploadedAt;
    private Instant committedAt;
    /** JSONB：{flows, qtyTotal, warehouses} */
    private String commitSummary;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getPersonOverride() { return personOverride; }
    public void setPersonOverride(String personOverride) { this.personOverride = personOverride; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRowsTotal() { return rowsTotal; }
    public void setRowsTotal(int rowsTotal) { this.rowsTotal = rowsTotal; }
    public int getRowsMapped() { return rowsMapped; }
    public void setRowsMapped(int rowsMapped) { this.rowsMapped = rowsMapped; }
    public int getRowsUnmapped() { return rowsUnmapped; }
    public void setRowsUnmapped(int rowsUnmapped) { this.rowsUnmapped = rowsUnmapped; }
    public String getUnmappedProducts() { return unmappedProducts; }
    public void setUnmappedProducts(String unmappedProducts) { this.unmappedProducts = unmappedProducts; }
    public String getUnresolvedPersons() { return unresolvedPersons; }
    public void setUnresolvedPersons(String unresolvedPersons) { this.unresolvedPersons = unresolvedPersons; }
    public String getParsedRows() { return parsedRows; }
    public void setParsedRows(String parsedRows) { this.parsedRows = parsedRows; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant committedAt) { this.committedAt = committedAt; }
    public String getCommitSummary() { return commitSummary; }
    public void setCommitSummary(String commitSummary) { this.commitSummary = commitSummary; }
}
