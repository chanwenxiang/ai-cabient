package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface BalanceRefundRequestMapper extends BaseTradeMapper<BalanceRefundRequest> {

    /** C20：行锁重查，审核入口在分布式锁内使用，避免锁外快照过期导致双渠道退。 */
    BalanceRefundRequest selectByIdForUpdateRaw(@Param("requestId") Long requestId);

    default Optional<BalanceRefundRequest> selectByIdForUpdate(Long requestId) {
        return Optional.ofNullable(selectByIdForUpdateRaw(requestId));
    }

    default Optional<BalanceRefundRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<BalanceRefundRequest>lambdaQuery()
                .eq(BalanceRefundRequest::getRequestNo, requestNo)));
    }

    default List<BalanceRefundRequest> findByUserIdOrderByCreatedAtDesc(Long userId, int limit) {
        int lim = Math.max(1, Math.min(limit, 50));
        return selectList(Wrappers.<BalanceRefundRequest>lambdaQuery()
                .eq(BalanceRefundRequest::getUserId, userId)
                .orderByDesc(BalanceRefundRequest::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByUserIdAndStatus(Long userId, String status) {
        return selectCount(Wrappers.<BalanceRefundRequest>lambdaQuery()
                .eq(BalanceRefundRequest::getUserId, userId)
                .eq(BalanceRefundRequest::getStatus, status));
    }
}
