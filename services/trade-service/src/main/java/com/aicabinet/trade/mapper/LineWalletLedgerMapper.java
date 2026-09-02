package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineWalletLedger;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface LineWalletLedgerMapper extends BaseTradeMapper<LineWalletLedger> {

    default List<LineWalletLedger> findByManagerIdOrderByCreatedAtDesc(Long managerId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return selectList(Wrappers.<LineWalletLedger>lambdaQuery()
                .eq(LineWalletLedger::getManagerId, managerId)
                .orderByDesc(LineWalletLedger::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default Optional<LineWalletLedger> findByRef(long managerId, String refType, String refId) {
        return Optional.ofNullable(selectOne(Wrappers.<LineWalletLedger>lambdaQuery()
                .eq(LineWalletLedger::getManagerId, managerId)
                .eq(LineWalletLedger::getRefType, refType)
                .eq(LineWalletLedger::getRefId, refId)
                .last("LIMIT 1")));
    }
}
