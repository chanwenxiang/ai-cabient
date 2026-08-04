package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface LineWithdrawRequestMapper extends BaseTradeMapper<LineWithdrawRequest> {

    default Optional<LineWithdrawRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getRequestNo, requestNo)));
    }

    default List<LineWithdrawRequest> findByManagerIdOrderByCreatedAtDesc(Long managerId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 50);
        return selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getManagerId, managerId)
                .orderByDesc(LineWithdrawRequest::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long sumAmountByManagerSince(Long managerId, Instant since) {
        List<LineWithdrawRequest> rows = selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getManagerId, managerId)
                .ge(LineWithdrawRequest::getCreatedAt, since)
                .in(LineWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (LineWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }
}
