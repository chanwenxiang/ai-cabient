package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@TableName("line_promo_task")
@Getter
@Setter
public class LinePromoTask {
    @TableId(type = IdType.AUTO)
    private Long taskId;
    private Long managerId;
    private String title;
    private String routeCode;
    private int targetQty;
    private int doneQty;
    private int bountyCents;
    private String status = "OPEN";
    private LocalDate dueDate;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
