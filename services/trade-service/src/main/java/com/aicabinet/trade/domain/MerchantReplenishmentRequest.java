package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_replenishment_request")
@Getter
@Setter
public class MerchantReplenishmentRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;

    private String merchantId;

    private String deviceId;

    private String status = "SUBMITTED";

    private String notes;

    private Long createdBy;

    private Instant submittedAt;

    private Instant reviewedAt;

    private Long reviewerId;

    private Long replenishmentTaskId;

    private Long outboundId;

    private String rejectReason;

    private Instant createdAt;

}
