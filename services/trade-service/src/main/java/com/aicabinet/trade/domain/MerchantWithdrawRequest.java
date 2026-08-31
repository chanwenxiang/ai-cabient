package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_withdraw_request")
@Getter
@Setter
public class MerchantWithdrawRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;
    private String requestNo;
    private String merchantId;
    /** 商户名称冗余 */
    private String merchantName;
    private Long amountCents;
    /** 提现手续费（分）；演示默认 0 */
    private Long feeCents;
    private String status;
    private String payChannel;
    private Long reviewerId;
    private String reviewRemark;
    private Instant reviewedAt;
    private String payoutRef;
    private String payoutMessage;
    private Instant paidAt;
    private Instant createdAt;
    private Instant updatedAt;

}
