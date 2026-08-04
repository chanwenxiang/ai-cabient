package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("merchant_role_template")
public class MerchantRoleTemplate {
    @TableId(type = IdType.INPUT)
    private String templateKey;
    private String templateName;
    private String description;
    private String permissionHint;
    private int sortOrder;

    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPermissionHint() { return permissionHint; }
    public void setPermissionHint(String permissionHint) { this.permissionHint = permissionHint; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
