package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "aliyun_category_mapping")
public class AliyunCategoryMapping {

    @Id
    @Column(name = "category_id", length = 64)
    private String categoryId;

    @Column(name = "category_name", length = 128)
    private String categoryName;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "min_confidence", nullable = false)
    private float minConfidence;

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public float getMinConfidence() { return minConfidence; }
    public void setMinConfidence(float minConfidence) { this.minConfidence = minConfidence; }
}
