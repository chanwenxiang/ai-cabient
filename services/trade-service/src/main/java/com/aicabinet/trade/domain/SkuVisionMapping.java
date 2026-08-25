package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Getter;
import lombok.Setter;

@TableName("sku_vision_mapping")
@Getter
@Setter
public class SkuVisionMapping {

    @TableId(type = IdType.INPUT)
    private String className;

    private String skuId;

    private float minConfidence;

    private String mappingSource = "YOLO_COCO";

}
