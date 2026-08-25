package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_replenishment_request_line")
@Getter
@Setter
public class MerchantReplenishmentRequestLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long requestId;

    private String skuId;

    private String skuName;

    private int suggestedQty;

    private int requestedQty;

}
