package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WalletAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 统一钱包账户 Mapper（复合主键 domain+owner_id）。
 *
 * <p>刻意<b>不</b>继承 {@code BaseTradeMapper}：那套 Spring-Data 风格
 * （{@code findById/save/selectById/updateById}）按单主键构造 SQL，对复合键会跨域误伤。
 * 这里只从 MP {@code BaseMapper} 取 {@code insert}/{@code selectList}（读列表按 domain 过滤安全），
 * 单账户读锁与更新一律走下方按 (domain, owner_id) 的显式 SQL。
 */
@Mapper
public interface WalletAccountMapper extends BaseMapper<WalletAccount> {

    WalletAccount findByOwnerRaw(@Param("domain") String domain, @Param("ownerId") String ownerId);

    WalletAccount findByOwnerForUpdateRaw(@Param("domain") String domain, @Param("ownerId") String ownerId);

    default Optional<WalletAccount> findByOwner(String domain, String ownerId) {
        return Optional.ofNullable(findByOwnerRaw(domain, ownerId));
    }

    /** 写路径行锁读（FOR UPDATE）——与旧 findByIdForUpdate 同款双层防护（分布式锁 + 行锁）。 */
    default Optional<WalletAccount> findByOwnerForUpdate(String domain, String ownerId) {
        return Optional.ofNullable(findByOwnerForUpdateRaw(domain, ownerId));
    }

    /** 只更新余额/冻结/更新时间，WHERE 锁死 (domain, owner_id)。 */
    int updateBalances(@Param("domain") String domain,
                       @Param("ownerId") String ownerId,
                       @Param("balanceCents") Long balanceCents,
                       @Param("frozenCents") Long frozenCents,
                       @Param("updatedAt") Instant updatedAt);

    /**
     * 欠款账龄探针（CB-086）：{@code balance_cents < 0} 的账户 + 「最后一次非负」时刻。
     *
     * <p>🔴 只读：不改余额/提现口径/柜机状态。余额为负是拍板内的正常形态（佣金与分账冲正挂负、
     * 押金直减，见 {@code MerchantWalletService#debitIfAbsentAllowNegative}），此查询只回答「负了多久」。</p>
     *
     * <p>返回列（LinkedHashMap，别名 c0/c1/c2）：c0=owner_id，c1=balance_cents（负数），
     * c2=arrears_since（最后一条 {@code balance_after >= 0} 的 created_at；整本账从未非负则取最早一行；
     * 无流水 ⇒ null ⇒ 调用方按「账龄不可判」跳过，不得当作 0 天）。</p>
     *
     * @param ownerId 非空时只查该商户（锁柜复机判据按商户单查）；null/空=全量（日结告警）
     */
    java.util.List<java.util.Map<String, Object>> findNegativeArrears(@Param("domain") String domain,
                                                                     @Param("ownerId") String ownerId);
}
