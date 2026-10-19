package com.aicabinet.trade.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 商户钱包流水读模型：由 {@code wallet_ledger}(domain=MERCHANT) 映射而来，V389 已删其同名表。 */
@Getter
@Setter
public class MerchantWalletLedger {

    private Long ledgerId;
    private String merchantId;
    /** 记账时快照的商户名，来源 {@code wallet_ledger.owner_snapshot_name} */
    private String merchantName;
    private String entryType;
    private Long amountCents;
    private Long balanceAfter;
    private Long frozenAfter;
    private String refType;
    private String refId;
    private String remark;
    private Instant createdAt;

}
