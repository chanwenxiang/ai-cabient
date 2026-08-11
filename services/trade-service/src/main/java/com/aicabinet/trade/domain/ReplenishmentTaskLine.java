package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("replenishment_task_line")
@Getter
@Setter
public class ReplenishmentTaskLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long taskId;

    private String lineType;

    private String skuId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private int quantity;

    private String slotId;

    private boolean applied;

    private Instant createdAt;

}
