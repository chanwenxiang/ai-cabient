package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("notification_template")
public class NotificationTemplate {

    @TableId(type = IdType.AUTO)
    private Long templateId;

    private String templateCode;

    private String templateName;

    private String channel = "IN_APP";

    private String channels = "IN_APP";

    private String category;

    private String titleTemplate;

    private String bodyTemplate;

    private String audience = "CONSUMER";

    private String status = "ACTIVE";

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitleTemplate() { return titleTemplate; }
    public void setTitleTemplate(String titleTemplate) { this.titleTemplate = titleTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
