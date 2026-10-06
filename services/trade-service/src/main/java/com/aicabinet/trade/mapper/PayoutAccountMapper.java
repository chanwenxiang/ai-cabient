package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.domain.PayoutAccountStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PayoutAccountMapper extends BaseTradeMapper<PayoutAccount> {

    /** 某主体的全部账户（含停用），默认账户排最前。 */
    default List<PayoutAccount> findByOwner(String ownerType, String ownerId) {
        return selectList(Wrappers.<PayoutAccount>lambdaQuery()
                .eq(PayoutAccount::getOwnerType, ownerType)
                .eq(PayoutAccount::getOwnerId, ownerId)
                .orderByDesc(PayoutAccount::getIsDefault)
                .orderByDesc(PayoutAccount::getAccountId));
    }

    /** 某主体的可用（ACTIVE）账户。 */
    default List<PayoutAccount> findActiveByOwner(String ownerType, String ownerId) {
        return selectList(Wrappers.<PayoutAccount>lambdaQuery()
                .eq(PayoutAccount::getOwnerType, ownerType)
                .eq(PayoutAccount::getOwnerId, ownerId)
                .eq(PayoutAccount::getStatus, PayoutAccountStatus.ACTIVE)
                .orderByDesc(PayoutAccount::getIsDefault)
                .orderByAsc(PayoutAccount::getAccountId));
    }

    /**
     * 取默认账户（先按默认标记，再退到最早创建的一个可用账户）。
     *
     * <p>退化路径的用意：历史主体可能没有设默认（老数据/迁移前建过），
     * 此时不该让提现直接失败，而是用最早的可用账户兜底并由上层告警。
     */
    default Optional<PayoutAccount> findDefault(String ownerType, String ownerId) {
        List<PayoutAccount> active = findActiveByOwner(ownerType, ownerId);
        return active.stream().filter(a -> Boolean.TRUE.equals(a.getIsDefault())).findFirst()
                .map(Optional::of)
                .orElseGet(() -> active.stream().findFirst());
    }

    default Optional<PayoutAccount> findByAccountId(long accountId) {
        return Optional.ofNullable(selectById(accountId));
    }

    /**
     * 设默认账户：先把同主体同类型的其他默认全部取消，再置本条为默认。
     *
     * <p>用一次 UPDATE 完成两步，避免「先清后置」中间态被并发读看到 0 个默认。
     * DB 部分唯一索引是最终防线，这里只是让常态路径不撞约束。
     */
    @Update("""
            UPDATE payout_account
               SET is_default = CASE WHEN account_id = #{accountId} THEN TRUE ELSE FALSE END,
                   updated_at = NOW()
             WHERE owner_type = #{ownerType}
               AND owner_id = #{ownerId}
               AND account_type = #{accountType}
               AND status = 'ACTIVE'
            """)
    int switchDefault(@Param("ownerType") String ownerType,
                      @Param("ownerId") String ownerId,
                      @Param("accountType") String accountType,
                      @Param("accountId") long accountId);

    /**
     * 该主体该类型是否已存在其他账户（用于「新增时是否要校验重复」）。
     */
    default boolean existsByOwnerAndType(String ownerType, String ownerId, String accountType) {
        return selectCount(Wrappers.<PayoutAccount>lambdaQuery()
                .eq(PayoutAccount::getOwnerType, ownerType)
                .eq(PayoutAccount::getOwnerId, ownerId)
                .eq(PayoutAccount::getAccountType, accountType)) > 0;
    }

    /** 幂等键是否已被占用（同一笔打款不允许两个单号）。 */
    @Select("SELECT COUNT(1) FROM merchant_withdraw_request WHERE idem_key = #{idemKey}")
    int countByIdemKey(@Param("idemKey") String idemKey);
}
