package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.StaffWalletAccount;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffWalletAccountMapper extends BaseTradeMapper<StaffWalletAccount> {

    /** DB 行锁读（FOR UPDATE）——审计 T4（2026-10-15）：对齐 LineWalletAccountMapper 双层防护
     * （分布式锁 + 行锁），防锁服务降级时并发冲正/发放竞态。 */
    StaffWalletAccount findByIdForUpdateRaw(@Param("staffUserId") long staffUserId);

    default Optional<StaffWalletAccount> findByIdForUpdate(long staffUserId) {
        return Optional.ofNullable(findByIdForUpdateRaw(staffUserId));
    }
}
