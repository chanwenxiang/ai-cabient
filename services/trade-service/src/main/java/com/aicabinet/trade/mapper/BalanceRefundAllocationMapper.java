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

    /** AU-137：按单标切片状态（渠道受理/成功/失败落行级权威，续退判定不再靠 request 状态推断）。 */
    default int updateStatusByRequestId(Long requestId, String status) {
        return update(null, Wrappers.<BalanceRefundAllocation>lambdaUpdate()
                .eq(BalanceRefundAllocation::getRequestId, requestId)
                .set(BalanceRefundAllocation::getStatus, status));
    }

    /** AU-137：按主键标单条切片状态。 */
    default int updateStatus(Long allocationId, String status) {
        return update(null, Wrappers.<BalanceRefundAllocation>lambdaUpdate()
                .eq(BalanceRefundAllocation::getAllocationId, allocationId)
                .set(BalanceRefundAllocation::getStatus, status));
    }
}
