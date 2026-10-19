package com.aicabinet.trade.service.wallet;

/**
 * 三域钱包判别列（V388 wallet_account/wallet_ledger 的 domain）。
 *
 * <p>每个域携带各自的<b>历史分布式锁前缀</b>：钱包写原语与对应提现服务
 * （{@code MerchantWithdrawService#runWithMerchantWalletLock} /
 * {@code LineWithdrawService#runWithLineWalletLock}）用<b>同一把 key</b> 做可重入互斥，
 * 锁串是跨服务契约，统一后<b>必须逐字保留</b>，否则钱包与提现不再互斥 ⇒ 并发双扣。
 */
public enum WalletDomain {

    MERCHANT("merchant:wallet:"),
    LINE("line:wallet:"),
    STAFF("staff-wallet:");

    private final String lockPrefix;

    WalletDomain(String lockPrefix) {
        this.lockPrefix = lockPrefix;
    }

    /** DB domain 判别列取值（与 V388 CHECK 一致：枚举 name()）。 */
    public String column() {
        return name();
    }

    /** 钱包/提现共享的分布式锁 key（沿用统一前的原语串，勿改）。 */
    public String lockKey(String ownerId) {
        return lockPrefix + ownerId;
    }
}
