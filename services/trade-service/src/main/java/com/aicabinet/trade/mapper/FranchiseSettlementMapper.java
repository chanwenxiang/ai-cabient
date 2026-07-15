package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.FranchiseSettlement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FranchiseSettlementMapper extends BaseTradeMapper<FranchiseSettlement> {

    default List<FranchiseSettlement> findByFranchiseId(Long franchiseId) {
    return selectList(Wrappers.<FranchiseSettlement>lambdaQuery().eq(FranchiseSettlement::getFranchiseId, franchiseId));
    }

    default Optional<FranchiseSettlement> findByFranchiseIdAndSettlementPeriod(Long franchiseId, String settlementPeriod) {
    return Optional.ofNullable(selectOne(Wrappers.<FranchiseSettlement>lambdaQuery().eq(FranchiseSettlement::getFranchiseId, franchiseId).eq(FranchiseSettlement::getSettlementPeriod, settlementPeriod)));
    }

    default List<FranchiseSettlement> findByStatus(String status) {
    return selectList(Wrappers.<FranchiseSettlement>lambdaQuery().eq(FranchiseSettlement::getStatus, status));
    }

}
