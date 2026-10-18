package com.aicabinet.trade.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 线长钱包流水读模型：由 {@code wallet_ledger}(domain=LINE) 映射而来，V389 已删其同名表。 */
@Getter
@Setter
public class LineWalletLedger {

    private Long ledgerId;
    private Long managerId;
    private String entryType;
    private Long amountCents;
    private Long balanceAfter;
    private Long frozenAfter;
    private String refType;
    private String refId;
    private String remark;
    private Instant createdAt;

}
