package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 外部渠道解析模板（CB-036，V347）。
 *
 * <p>每渠道一条；列映射 {@code columnMap}（JSONB，语义字段→外部列头）。
 * 六渠道结构实证见 {@code docs/SATELLITE_WAREHOUSE.md} 与任务台账（2026-10-13）。
 *
 * <p>语义字段：extId/extName/brand/spec/unitPrice/qty/qtyBefore/qtyAfter/person/happenedAt。
 */
@TableName("external_import_template")
public class ExternalImportTemplate {

    public static final String FILE_CSV_GBK = "CSV_GBK";
    public static final String FILE_XLSX = "XLSX";
    public static final String FILE_XLS = "XLS";

    public static final String SHEET_FIRST = "FIRST";
    public static final String SHEET_ALL = "ALL";

    public static final String QTY_DIRECT = "DIRECT";
    public static final String QTY_BY_DIFF = "BY_DIFF";
    public static final String QTY_SALES = "SALES";
    /** 实盘数（V348 10日盘点导入）：qty 列直接就是实盘件数，非差量非销量。 */
    public static final String QTY_STOCKTAKE_COUNT = "STOCKTAKE_COUNT";

    public static final String KEY_PRODUCT_ID = "PRODUCT_ID";
    public static final String KEY_NAME = "NAME";
    /** 本系统 SKU 直用（V348 盘点导入：Excel 内 SKU 已是本系统 skuId，无需映射治理）。 */
    public static final String KEY_SKU_ID = "SKU_ID";

    public static final String PERSON_SHEET_NAME = "SHEET_NAME";
    public static final String PERSON_COLUMN = "COLUMN";
    public static final String PERSON_FILE_NAME = "FILE_NAME";
    public static final String PERSON_MANUAL = "MANUAL";

    public static final String OP_WAREHOUSE_OUT = "WAREHOUSE_OUT";
    public static final String OP_WAREHOUSE_STOCKTAKE = "WAREHOUSE_STOCKTAKE";
    public static final String OP_GUEST_WATER = "GUEST_WATER";

    @TableId(type = IdType.AUTO)
    private Long templateId;

    private String channelCode;
    private String channelName;
    private String fileType;
    private String sheetStrategy;
    private String qtySemantics;
    private String keyField;
    private String personSource;
    private String opType;
    /** JSONB 字符串：语义字段→外部列头 */
    private String columnMap;
    private String defaultWarehouseId;
    private boolean enabled;
    private String remark;
    private Instant updatedAt;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getSheetStrategy() { return sheetStrategy; }
    public void setSheetStrategy(String sheetStrategy) { this.sheetStrategy = sheetStrategy; }
    public String getQtySemantics() { return qtySemantics; }
    public void setQtySemantics(String qtySemantics) { this.qtySemantics = qtySemantics; }
    public String getKeyField() { return keyField; }
    public void setKeyField(String keyField) { this.keyField = keyField; }
    public String getPersonSource() { return personSource; }
    public void setPersonSource(String personSource) { this.personSource = personSource; }
    public String getOpType() { return opType; }
    public void setOpType(String opType) { this.opType = opType; }
    public String getColumnMap() { return columnMap; }
    public void setColumnMap(String columnMap) { this.columnMap = columnMap; }
    public String getDefaultWarehouseId() { return defaultWarehouseId; }
    public void setDefaultWarehouseId(String defaultWarehouseId) { this.defaultWarehouseId = defaultWarehouseId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
