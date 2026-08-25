package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 仓库货位。 */
@TableName("warehouse_bin")
@Getter
@Setter
public class WarehouseBin {

    @TableId(type = IdType.AUTO)
    private Long binId;
    private String warehouseId;
    private String binCode;
    private String binName;
    private String status = "ACTIVE";
    private Instant createdAt;

}
