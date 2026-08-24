package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.common.dto.SkuCatalogDto;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("sku_catalog")
@Getter
@Setter
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

    @TableField("category_id")
    private String categoryId;

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


}
