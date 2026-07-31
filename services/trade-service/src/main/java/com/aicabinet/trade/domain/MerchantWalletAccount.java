package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("merchant_wallet_account")
public class MerchantWalletAccount {

    @TableId
    private String merchantId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public Long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(Long balanceCents) { this.balanceCents = balanceCents; }
    public Long getFrozenCents() { return frozenCents; }
    public void setFrozenCents(Long frozenCents) { this.frozenCents = frozenCents; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
