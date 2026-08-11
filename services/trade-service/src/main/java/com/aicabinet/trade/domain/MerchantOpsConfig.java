package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_ops_config")
@Getter
@Setter
public class MerchantOpsConfig {
    @TableId(type = IdType.INPUT)
    private String merchantId;
    private String stockingType;
    private int stockoutThresholdPct;
    private String tallyMode;
    private Boolean useStockingList;
    private String replenishInputType;
    private Boolean photoStocktake;
    private Boolean photoReplenish;
    private int maxInflightOrders;
    private Instant updatedAt;

}
