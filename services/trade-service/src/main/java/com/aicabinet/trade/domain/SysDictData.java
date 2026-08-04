package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("sys_dict_data")
public class SysDictData {

    @TableId(type = IdType.AUTO)
    private Long dictDataId;

    private String dictType;

    private String dictValue;

    private String dictLabel;

    private int sortOrder = 0;

    private String status = "ACTIVE";

    private String remark;

    private Instant createdAt;

    private Instant updatedAt;

public Long getDictDataId() { return dictDataId; }
    public void setDictDataId(Long dictDataId) { this.dictDataId = dictDataId; }
    public String getDictType() { return dictType; }
    public void setDictType(String dictType) { this.dictType = dictType; }
    public String getDictValue() { return dictValue; }
    public void setDictValue(String dictValue) { this.dictValue = dictValue; }
    public String getDictLabel() { return dictLabel; }
    public void setDictLabel(String dictLabel) { this.dictLabel = dictLabel; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
