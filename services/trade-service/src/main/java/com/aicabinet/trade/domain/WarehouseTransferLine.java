package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("warehouse_transfer_line")
@Getter
@Setter
public class WarehouseTransferLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long transferId;
    private String skuId;
    private String batchNo = "";
    private LocalDate expiryDate;
    private int quantity;

    /**
     * V314：实收数量。DB CHECK 保证 {@code receivedQty + lossQty == quantity}。
     * <p>默认 0；历史行由迁移回填为 quantity。
     */
    private int receivedQty;

    /**
     * V314：在途损耗数量（发运减实收）。>0 时 {@code lossReason} 必填（DB CHECK 强制）。
     */
    private int lossQty;

    /** V314：在途损耗原因。自由文本；分类语义与 WriteOffReasonCategory 一致。 */
    private String lossReason;

    /** V314：在途损耗备注（理赔/说明）。 */
    private String lossNote;
}
