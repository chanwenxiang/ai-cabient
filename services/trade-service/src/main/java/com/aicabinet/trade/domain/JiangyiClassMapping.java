package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑 class_id → 我方 SKU 映射（CB-022，V333）。
 * <p>将邑识别上报只带 classId（无金额、无 SKU）：结算金额 = Σ(映射 SKU 当前价 × qty)，
 * 金额绝不采信设备侧。映射未命中 fail-closed 转 DISPUTED。</p>
 *
 * <p>source：MANUAL 人工建立 | MODEL_SYNC 模型 classes 同步预生成（二期）；
 * status：ACTIVE 生效 | DISABLED 预生成先禁用、人工确认后激活（防误映射直接扣款）。</p>
 */
@TableName("jiangyi_class_mapping")
@Getter
@Setter
public class JiangyiClassMapping {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private Integer classId;

    private String modelName;

    /** 模型类别名（getStdSkuQueryByTextNames 的 textName，展示/核对用）。 */
    private String textName;

    /** 我方 SKU ID（sku.sku_id VARCHAR(64) 同域）。 */
    private String skuId;

    private String status;

    private String source;

    private Instant createdAt;

    private Instant updatedAt;
}
