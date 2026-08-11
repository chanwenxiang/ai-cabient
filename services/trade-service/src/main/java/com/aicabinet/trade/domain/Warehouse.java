package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse")
@Getter
@Setter
public class Warehouse {

    @TableId(type = IdType.INPUT)
    private String warehouseId;

    private String warehouseName;

    private String address;

    private String status = "ACTIVE";

    private Instant createdAt;

}
