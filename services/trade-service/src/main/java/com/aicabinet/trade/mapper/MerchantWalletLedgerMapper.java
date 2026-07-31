package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantWalletLedgerMapper extends BaseTradeMapper<MerchantWalletLedger> {

    default List<MerchantWalletLedger> findByMerchantIdOrderByCreatedAtDesc(String merchantId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return selectList(Wrappers.<MerchantWalletLedger>lambdaQuery()
                .eq(MerchantWalletLedger::getMerchantId, merchantId)
                .orderByDesc(MerchantWalletLedger::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default Optional<MerchantWalletLedger> findByRef(String merchantId, String refType, String refId) {
        return Optional.ofNullable(selectOne(Wrappers.<MerchantWalletLedger>lambdaQuery()
                .eq(MerchantWalletLedger::getMerchantId, merchantId)
                .eq(MerchantWalletLedger::getRefType, refType)
                .eq(MerchantWalletLedger::getRefId, refId)
                .last("LIMIT 1")));
    }
}
