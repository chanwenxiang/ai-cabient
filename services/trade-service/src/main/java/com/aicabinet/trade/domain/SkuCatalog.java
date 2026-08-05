package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.common.dto.SkuCatalogDto;
import java.time.Instant;

@TableName("sku_catalog")
public class SkuCatalog {

    @TableId(type = IdType.INPUT)
    private String skuId;

    private Long skuCode;

    private String skuName;

    private int priceCents;

    private Integer weightGrams;

    private boolean visionEnabled = true;

    private String imageUrl;

    private String description;

    private String category;

    private String barcode;

    private String brand;

    private String spec;

    private String unit = "件";

    private String status = "ACTIVE";

    private float minChargeConfidence = 0.92f;

    private String yoloClassName;

    private String visionEnrollmentStatus = "DRAFT";

    private float detectionMinConfidence = 0.5f;

    @TableField("reference_image_urls")
    private String referenceImageUrlsJson;

    private Integer shelfLifeDays;

    private int nearExpiryDays = 7;

    private int blockSaleDaysBeforeExpiry = 0;

    private String storageType = "AMBIENT";

    private Integer purchaseCostCents;

    private Integer nearExpiryPriceCents;

    private Integer maxPriceCents;

    private Instant createdAt;

    @TableField("updated_by_user_id")
    private Long updatedByUserId;

    @TableField("updated_by_name")
    private String updatedByName;

    public SkuCatalogDto toDto() {
        return new SkuCatalogDto(
                skuId,
                skuCode,
                skuName,
                priceCents,
                weightGrams,
                visionEnabled,
                imageUrl,
                description,
                category,
                barcode,
                brand,
                spec,
                unit,
                status,
                shelfLifeDays,
                nearExpiryDays,
                blockSaleDaysBeforeExpiry,
                storageType,
                purchaseCostCents,
                nearExpiryPriceCents,
                maxPriceCents,
                minChargeConfidence,
                yoloClassName,
                visionEnrollmentStatus,
                detectionMinConfidence,
                referenceImageUrlsJson,
                createdAt,
                updatedByUserId,
                updatedByName
        );
    }

    public float getMinChargeConfidence() { return minChargeConfidence; }
    public void setMinChargeConfidence(float minChargeConfidence) { this.minChargeConfidence = minChargeConfidence; }

    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public Long getSkuCode() { return skuCode; }
    public void setSkuCode(Long skuCode) { this.skuCode = skuCode; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getPriceCents() { return priceCents; }
    public void setPriceCents(int priceCents) { this.priceCents = priceCents; }
    public Integer getWeightGrams() { return weightGrams; }
    public void setWeightGrams(Integer weightGrams) { this.weightGrams = weightGrams; }
    public boolean isVisionEnabled() { return visionEnabled; }
    public void setVisionEnabled(boolean visionEnabled) { this.visionEnabled = visionEnabled; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public int getNearExpiryDays() { return nearExpiryDays; }
    public void setNearExpiryDays(int nearExpiryDays) { this.nearExpiryDays = nearExpiryDays; }
    public int getBlockSaleDaysBeforeExpiry() { return blockSaleDaysBeforeExpiry; }
    public void setBlockSaleDaysBeforeExpiry(int blockSaleDaysBeforeExpiry) {
        this.blockSaleDaysBeforeExpiry = blockSaleDaysBeforeExpiry;
    }
    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }
    public Integer getPurchaseCostCents() { return purchaseCostCents; }
    public void setPurchaseCostCents(Integer purchaseCostCents) { this.purchaseCostCents = purchaseCostCents; }
    public Integer getNearExpiryPriceCents() { return nearExpiryPriceCents; }
    public void setNearExpiryPriceCents(Integer nearExpiryPriceCents) { this.nearExpiryPriceCents = nearExpiryPriceCents; }
    public Integer getMaxPriceCents() { return maxPriceCents; }
    public void setMaxPriceCents(Integer maxPriceCents) { this.maxPriceCents = maxPriceCents; }
    public String getYoloClassName() { return yoloClassName; }
    public void setYoloClassName(String yoloClassName) { this.yoloClassName = yoloClassName; }
    public String getVisionEnrollmentStatus() { return visionEnrollmentStatus; }
    public void setVisionEnrollmentStatus(String visionEnrollmentStatus) { this.visionEnrollmentStatus = visionEnrollmentStatus; }
    public float getDetectionMinConfidence() { return detectionMinConfidence; }
    public void setDetectionMinConfidence(float detectionMinConfidence) { this.detectionMinConfidence = detectionMinConfidence; }
    public String getReferenceImageUrlsJson() { return referenceImageUrlsJson; }
    public void setReferenceImageUrlsJson(String referenceImageUrlsJson) { this.referenceImageUrlsJson = referenceImageUrlsJson; }
}
