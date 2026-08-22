package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("merchant_payment_onboarding")
@Getter
@Setter
public class MerchantPaymentOnboarding {
    @TableId(type = IdType.AUTO)
    private Long onboardingId;
    private String merchantId;
    private String channel;
    private String status = "DRAFT";
    private String externalMchId;
    private String externalRef;
    private String note;
    private Instant lastSyncedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
