package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("sla_daily_snapshot")
@Getter
@Setter
public class SlaDailySnapshot {

    @TableId(type = IdType.INPUT)
    private LocalDate snapshotDate;

    private int doorOpenAttempts;

    private int doorOpenSuccess;

    private Float doorSuccessRate;

    private Long avgRecognizeMs;

    private Long p95RecognizeMs;

    private int deviceTotal;

    private int deviceOnlinePeak;

    private Float deviceOnlineRate;

    private Instant createdAt;

}
