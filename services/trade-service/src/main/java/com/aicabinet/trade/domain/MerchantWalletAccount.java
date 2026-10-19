package com.aicabinet.trade.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 商户钱包读模型：由 {@code wallet_account}(domain=MERCHANT) 映射而来，V389 已删其同名表。 */
@Getter
@Setter
public class MerchantWalletAccount {

    private String merchantId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

}
