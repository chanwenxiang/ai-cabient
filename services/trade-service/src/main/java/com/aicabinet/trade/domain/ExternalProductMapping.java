package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 外部商品 → 本系统 SKU 映射（CB-036，V347）。
 *
 * <p>一个外部商品至多绑一个本系统 SKU（{@code external_product_id} 唯一）；
 * 反向一对多合法（外部多规格归同一本系统 SKU）。
 */
@TableName("external_product_mapping")
public class ExternalProductMapping {

    @TableId(type = IdType.AUTO)
    private Long mappingId;

    private Long externalProductId;
    private String skuId;
    private Long boundBy;
    private Instant boundAt;
    private String note;

    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long mappingId) { this.mappingId = mappingId; }
    public Long getExternalProductId() { return externalProductId; }
    public void setExternalProductId(Long externalProductId) { this.externalProductId = externalProductId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public Long getBoundBy() { return boundBy; }
    public void setBoundBy(Long boundBy) { this.boundBy = boundBy; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
