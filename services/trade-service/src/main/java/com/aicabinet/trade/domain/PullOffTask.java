package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("pull_off_task")
@Getter
@Setter
public class PullOffTask {

    @TableId(type = IdType.AUTO)
    private Long taskId;

    private String deviceId;

    private String skuId;

    private String lotId;

    private String batchNo;

    private int quantity = 1;

    private String reason;

    private String status = "OPEN";

    private Instant createdAt;

    private Instant resolvedAt;

}
