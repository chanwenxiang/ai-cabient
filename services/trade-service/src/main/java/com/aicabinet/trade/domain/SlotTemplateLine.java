package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** C3 商品模板行：货道 × SKU × 售价 × 目层库存。 */
@TableName("slot_template_line")
public class SlotTemplateLine {

    @TableId(value = "id", type = IdType.AUTO)
    private Long lineId;

    private Long templateId;

    private String slotCode;

    private int rowNo;

    private int colNo;

    private String skuId;

    private long priceCents;

    private int parLevel;

    public Long getLineId() { return lineId; }
    public void setLineId(Long v) { this.lineId = v; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long v) { this.templateId = v; }
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String v) { this.slotCode = v; }
    public int getRowNo() { return rowNo; }
    public void setRowNo(int v) { this.rowNo = v; }
    public int getColNo() { return colNo; }
    public void setColNo(int v) { this.colNo = v; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String v) { this.skuId = v; }
    public long getPriceCents() { return priceCents; }
    public void setPriceCents(long v) { this.priceCents = v; }
    public int getParLevel() { return parLevel; }
    public void setParLevel(int v) { this.parLevel = v; }
}
