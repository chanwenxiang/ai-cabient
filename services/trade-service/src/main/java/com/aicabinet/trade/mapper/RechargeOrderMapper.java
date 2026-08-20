package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RechargeOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface RechargeOrderMapper extends BaseTradeMapper<RechargeOrder> {

    default Optional<RechargeOrder> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(selectOne(Wrappers.<RechargeOrder>lambdaQuery().eq(RechargeOrder::getIdempotencyKey, idempotencyKey)));
    }

        long sumPaidAmountBetween(@Param("start") Instant start, @Param("end") Instant end);


        List<String> findPaidOrderIdsBetween(@Param("start") Instant start, @Param("end") Instant end);


        List<RechargeOrder> findPaidBetween(@Param("start") Instant start, @Param("end") Instant end);


    default Page<RechargeOrder> search( @Param("status") String status, @Param("userId") Long userId, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RechargeOrder>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<RechargeOrder>lambdaQuery()
                .eq(status != null && !status.isEmpty(), RechargeOrder::getStatus, status)
                .eq(userId != null, RechargeOrder::getUserId, userId)
                .orderByDesc(RechargeOrder::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default List<RechargeOrder> findByCreatedAtAfter(Instant since) {
        return selectList(Wrappers.<RechargeOrder>lambdaQuery()
                .ge(RechargeOrder::getCreatedAt, since)
                .orderByAsc(RechargeOrder::getCreatedAt));
    }

    default List<RechargeOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff) {
        return findByStatusAndCreatedAtBefore(status, cutoff, 500);
    }

    default List<RechargeOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<RechargeOrder>lambdaQuery()
                .eq(RechargeOrder::getStatus, status)
                .lt(RechargeOrder::getCreatedAt, cutoff)
                .orderByAsc(RechargeOrder::getCreatedAt)
                .last("LIMIT " + lim));
    }

    /** 可原路退的已支付充值单（FIFO）。 */
    default List<RechargeOrder> findRefundablePaidByUser(Long userId) {
        return selectList(Wrappers.<RechargeOrder>lambdaQuery()
                .eq(RechargeOrder::getUserId, userId)
                .eq(RechargeOrder::getStatus, "PAID")
                .orderByAsc(RechargeOrder::getPaidAt)
                .orderByAsc(RechargeOrder::getCreatedAt));
    }

}
