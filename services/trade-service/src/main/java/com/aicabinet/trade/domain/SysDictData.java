package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sys_dict_data")
public class SysDictData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long dictDataId;

    @Column(nullable = false, length = 64)
    private String dictType;

    @Column(nullable = false, length = 64)
    private String dictValue;

    @Column(nullable = false, length = 128)
    private String dictLabel;

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(length = 256)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getDictDataId() { return dictDataId; }
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
}
