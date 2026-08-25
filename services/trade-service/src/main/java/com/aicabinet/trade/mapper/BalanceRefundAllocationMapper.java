package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.BalanceRefundAllocation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BalanceRefundAllocationMapper extends BaseTradeMapper<BalanceRefundAllocation> {

    default List<BalanceRefundAllocation> findByRequestId(Long requestId) {
        return selectList(Wrappers.<BalanceRefundAllocation>lambdaQuery()
                .eq(BalanceRefundAllocation::getRequestId, requestId)
                .orderByAsc(BalanceRefundAllocation::getAllocationId));
    }
}
