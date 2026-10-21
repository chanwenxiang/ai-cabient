package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑 class_id → 我方 SKU 映射（CB-022，V333）。
 * <p>本表是识别解析的<b>兜底通道</b>：解析优先用上报里的 thirdPid 走商品库挂接
 * （{@code sku_jiangyi_link}，2026-10-21 修订——固件实发 V16 §5.2.9 富字段形态，
 * 并非「只带 classId」），thirdPid 缺失或未挂接时才用本表。
 * 结算金额 = Σ(我方 SKU 现价 × qty)，厂商上报的 price 只留档不采信（A 档，CB-062）；
 * 两者都未命中 fail-closed 转 DISPUTED。</p>
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
