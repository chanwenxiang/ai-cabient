package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "payment_reconciliation", autoResultMap = true)
@Getter
@Setter
public class PaymentReconciliation {

    @TableId(type = IdType.AUTO)
    private Long reconId;

    private LocalDate reconDate;

    private String channel;

    private long platformTotal;

    private long ledgerTotal;

    private long diffCents;

    private int matchedCount;

    private int unmatchedCount;

    private String status = "PENDING";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String detail;

    private Instant createdAt;

    private Instant completedAt;

}
