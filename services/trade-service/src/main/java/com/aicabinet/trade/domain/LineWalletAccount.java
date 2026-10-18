package com.aicabinet.trade.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 线长钱包读模型：由 {@code wallet_account}(domain=LINE) 映射而来，V389 已删其同名表。 */
@Getter
@Setter
public class LineWalletAccount {

    private Long managerId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

}
