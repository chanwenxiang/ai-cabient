package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** C3 商品模板（铺货模板）头表：名称 + 创建人。 */
@TableName("slot_template")
public class SlotTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long templateId;

    private String templateName;

    private Long createdBy;

    private Instant createdAt;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long v) { this.templateId = v; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String v) { this.templateName = v; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
