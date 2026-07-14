package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sku_vision_mapping")
public class SkuVisionMapping {

    @Id
    @Column(name = "class_name", length = 64)
    private String className;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "min_confidence", nullable = false)
    private float minConfidence;

    @Column(name = "mapping_source", nullable = false, length = 32)
    private String mappingSource = "YOLO_COCO";

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public float getMinConfidence() { return minConfidence; }
    public void setMinConfidence(float minConfidence) { this.minConfidence = minConfidence; }
    public String getMappingSource() { return mappingSource; }
    public void setMappingSource(String mappingSource) { this.mappingSource = mappingSource; }
}
