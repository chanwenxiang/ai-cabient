package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Getter;
import lombok.Setter;

@TableName("aliyun_category_mapping")
@Getter
@Setter
public class AliyunCategoryMapping {

    @TableId(type = IdType.INPUT)
    private String categoryId;

    private String categoryName;

    private String skuId;

    private float minConfidence;

}
