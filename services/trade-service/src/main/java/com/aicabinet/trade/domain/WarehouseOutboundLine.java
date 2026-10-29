package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_outbound_line")
@Getter
@Setter
public class WarehouseOutboundLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long outboundId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private LocalDate expiryDate;

    private int quantity;

    /**
     * 领而未上件数 = {@link #quantity} − 现场实收（CB-074，V414）。
     * 0=全量上柜；&gt;0 是未结差额，货仍在补货员手上，不进分仓账也不进柜机账。
     */
    private int remainderQty;

    private boolean picked;
    private String handoverStatus = "PENDING";

    /** 目标货道（规划按货道拆行时写入） */
    private String slotId;

}
