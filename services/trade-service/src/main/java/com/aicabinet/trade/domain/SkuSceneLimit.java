package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品×场景上架限额（CB-049 扩展，V370）：
 * 一个 SKU 可对多个投放场景各配一个上限；柜内该场景下该 SKU 的
 * 最大容量合计不得大于 maxQty（绑定/容量变更/模板套用时硬校验）。
 */
@TableName("sku_scene_limit")
@Getter
@Setter
public class SkuSceneLimit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;

    /** 取值同 device_info.scene_type（字典 device_scene_type，CB-049） */
    private String sceneType;

    /** 该场景下柜内上架合计上限（>0） */
    private Integer maxQty;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
