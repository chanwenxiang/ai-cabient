package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

@TableName("sku_vision_mapping")
public class SkuVisionMapping {

    @TableId(type = IdType.INPUT)
    private String className;

    private String skuId;

    private float minConfidence;

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
