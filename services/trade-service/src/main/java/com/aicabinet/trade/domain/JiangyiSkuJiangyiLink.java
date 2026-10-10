package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑商品 ↔ 我方 SKU 挂接（CB-023，V335）：自研视觉链路与将邑链路并行互不污染，
 * 本表是「商品维度」的将邑归属（识别映射的 sku_id 仍落在 jiangyi_class_mapping 设备维度）。
 * <p>sync_status：BOUND 挂接既有将邑商品 / CREATED 我方发起新增成功 / RETIRED 解挂留痕。</p>
 */
@TableName("sku_jiangyi_link")
@Getter
@Setter
public class JiangyiSkuJiangyiLink {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String skuId;

    /** 将邑商品 id（§4.1.5 data / §4.1.2 id），UNIQUE。 */
    private String jiangyiProductId;

    /** 将邑侧 name 快照（排障用）。 */
    private String jiangyiName;

    /** 学习完成后从 §4.4.2 回填（classes.txt 的直接键，可空）。 */
    private String jiangyiTextName;

    /** 将邑 barCode 快照（可空——barCodeSource=manual 不可靠，仅 bind 时一致性校验用）。 */
    private String barCode;

    private String syncStatus;

    private Instant syncedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
