package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("sys_dict_type")
public class SysDictType {

    @TableId(type = IdType.INPUT)
    private String dictType;

    private String dictName;

    private String status = "ACTIVE";

    private String remark;

    private int sortOrder = 0;

    private Instant createdAt;

    private Instant updatedAt;

public String getDictType() { return dictType; }
    public void setDictType(String dictType) { this.dictType = dictType; }
    public String getDictName() { return dictName; }
    public void setDictName(String dictName) { this.dictName = dictName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
