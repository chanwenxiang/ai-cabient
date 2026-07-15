package com.aicabinet.trade.domain;

import com.aicabinet.common.dto.SkuCatalogDto;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "sku_catalog")
public class SkuCatalog {

    @Id
    @Column(length = 64)
    private String skuId;

    @Column(nullable = false, length = 128)
    private String skuName;

    @Column(nullable = false)
    private int priceCents;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "vision_enabled", nullable = false)
    private boolean visionEnabled = true;

    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category;

    @Column(length = 64)
    private String barcode;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "min_charge_confidence", nullable = false)
    private float minChargeConfidence = 0.92f;

    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    @Column(name = "near_expiry_days", nullable = false)
    private int nearExpiryDays = 7;

    @Column(name = "block_sale_days_before_expiry", nullable = false)
    private int blockSaleDaysBeforeExpiry = 0;

    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType = "AMBIENT";

    @Column(name = "purchase_cost_cents")
    private Integer purchaseCostCents;

    @Column(name = "near_expiry_price_cents")
    private Integer nearExpiryPriceCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
    }

    public SkuCatalogDto toDto() {
        return new SkuCatalogDto(
                skuId,
                skuName,
                priceCents,
                weightGrams,
                visionEnabled,
                imageUrl,
                description,
                category,
                barcode,
                status,
                shelfLifeDays,
                nearExpiryDays,
                blockSaleDaysBeforeExpiry,
                storageType,
                purchaseCostCents,
                nearExpiryPriceCents,
                createdAt
        );
    }

    public float getMinChargeConfidence() { return minChargeConfidence; }
    public void setMinChargeConfidence(float minChargeConfidence) { this.minChargeConfidence = minChargeConfidence; }

    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
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
}
