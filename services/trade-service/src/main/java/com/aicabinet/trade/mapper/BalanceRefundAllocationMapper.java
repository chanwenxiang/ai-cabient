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

    /** 渠道幂等号反查切片；重复确认/失败只推进一次。 */
    default java.util.Optional<BalanceRefundAllocation> findByOutRefundNo(String outRefundNo) {
        return java.util.Optional.ofNullable(selectOne(Wrappers.<BalanceRefundAllocation>lambdaQuery()
                .eq(BalanceRefundAllocation::getOutRefundNo, outRefundNo)));
    }

    default java.util.Optional<BalanceRefundAllocation> findByOutRefundNoForUpdate(String outRefundNo) {
        return java.util.Optional.ofNullable(selectOne(Wrappers.<BalanceRefundAllocation>lambdaQuery()
                .eq(BalanceRefundAllocation::getOutRefundNo, outRefundNo)
                .last("FOR UPDATE")));
    }

    /** 切片状态 CAS，防止迟到 SUCCESS 覆盖 ABNORMAL/FAILED。 */
    default int updateStatusIfCurrent(Long allocationId, String expectedStatus, String nextStatus) {
        return update(null, Wrappers.<BalanceRefundAllocation>lambdaUpdate()
                .eq(BalanceRefundAllocation::getAllocationId, allocationId)
                .eq(BalanceRefundAllocation::getStatus, expectedStatus)
                .set(BalanceRefundAllocation::getStatus, nextStatus));
    }
}
