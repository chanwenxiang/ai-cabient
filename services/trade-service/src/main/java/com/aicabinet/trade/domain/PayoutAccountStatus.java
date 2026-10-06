package com.aicabinet.trade.domain;

/** 收款账户状态字面量。 */
public final class PayoutAccountStatus {

    private PayoutAccountStatus() {
    }

    /** 可用于新提现申请。 */
    public static final String ACTIVE = "ACTIVE";
    /** 已停用：不可用于<b>新</b>申请，但已成立的提现单快照不受影响（照常打款）。 */
    public static final String DISABLED = "DISABLED";
}
