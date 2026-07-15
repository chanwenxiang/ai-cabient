package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineManagerSettlement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface LineManagerSettlementMapper extends BaseTradeMapper<LineManagerSettlement> {

    default List<LineManagerSettlement> findByManagerId(Long managerId) {
    return selectList(Wrappers.<LineManagerSettlement>lambdaQuery().eq(LineManagerSettlement::getManagerId, managerId));
    }

    default Optional<LineManagerSettlement> findByManagerIdAndSettlementPeriod(Long managerId, String settlementPeriod) {
    return Optional.ofNullable(selectOne(Wrappers.<LineManagerSettlement>lambdaQuery().eq(LineManagerSettlement::getManagerId, managerId).eq(LineManagerSettlement::getSettlementPeriod, settlementPeriod)));
    }

    default List<LineManagerSettlement> findByStatus(String status) {
    return selectList(Wrappers.<LineManagerSettlement>lambdaQuery().eq(LineManagerSettlement::getStatus, status));
    }

}
