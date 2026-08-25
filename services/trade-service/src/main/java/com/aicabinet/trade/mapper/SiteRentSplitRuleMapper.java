package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SiteRentSplitRule;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SiteRentSplitRuleMapper extends BaseTradeMapper<SiteRentSplitRule> {
    default List<SiteRentSplitRule> findByContractId(Long contractId) {
        return selectList(Wrappers.<SiteRentSplitRule>lambdaQuery()
                .eq(SiteRentSplitRule::getContractId, contractId)
                .orderByAsc(SiteRentSplitRule::getRuleId));
    }

    default void deleteByContractId(Long contractId) {
        delete(Wrappers.<SiteRentSplitRule>lambdaQuery()
                .eq(SiteRentSplitRule::getContractId, contractId));
    }
}
