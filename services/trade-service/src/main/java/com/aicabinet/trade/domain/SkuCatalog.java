package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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

    private float detectionMinConfidence = 0.5f;

    private Integer shelfLifeDays;

    private int nearExpiryDays = 7;

    private int blockSaleDaysBeforeExpiry = 0;

    private String storageType = "AMBIENT";

    private Integer purchaseCostCents;

    private Integer nearExpiryPriceCents;

    private Integer maxPriceCents;

    /** 显式最低限价（V350，CB-037）：null=不限（仅要求 >0），支持清仓/引流打折；低于成本由前端提醒。 */
    private Integer minPriceCents;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField("is_deleted")
    private Boolean deleted;

    @TableField("updated_by_user_id")
    private Long updatedByUserId;

    @TableField("updated_by_name")
    private String updatedByName;

    /** 经营归属（V349，字典 sku_operation_scope：DIRECT/NON_DIRECT；NULL=未分类，见 CB-036）。 */
    private String operationScope;

    public SkuCatalogDto toDto() {
        return toDto(null);
    }

    /** admin 读路径用：带商品×场景上架限额（CB-049 扩展）；其他消费方传 null。 */
    public SkuCatalogDto toDto(java.util.List<com.aicabinet.common.dto.SkuSceneLimitItem> sceneLimits) {
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
                minPriceCents,
                minChargeConfidence,
                detectionMinConfidence,
                createdAt,
                updatedByUserId,
                updatedByName,
                operationScope,
                sceneLimits
        );
    }


}
