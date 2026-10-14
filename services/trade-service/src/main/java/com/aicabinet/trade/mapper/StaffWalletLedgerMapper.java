package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.StaffWalletLedger;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Optional;

public interface StaffWalletLedgerMapper extends BaseTradeMapper<StaffWalletLedger> {

    /** 幂等查重：相同主体+refType+refId 的流水是否已存在。 */
    default Optional<StaffWalletLedger> findByRef(long staffUserId, String refType, String refId) {
        return Optional.ofNullable(selectOne(Wrappers.<StaffWalletLedger>lambdaQuery()
                .eq(StaffWalletLedger::getStaffUserId, staffUserId)
                .eq(StaffWalletLedger::getRefType, refType)
                .eq(StaffWalletLedger::getRefId, refId)
                .last("LIMIT 1")));
    }
}
