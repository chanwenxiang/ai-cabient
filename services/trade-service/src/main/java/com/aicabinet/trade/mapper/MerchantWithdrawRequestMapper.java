package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantWithdrawRequestMapper extends BaseTradeMapper<MerchantWithdrawRequest> {

    default Optional<MerchantWithdrawRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getRequestNo, requestNo)));
    }

    default List<MerchantWithdrawRequest> findByMerchantIdOrderByCreatedAtDesc(String merchantId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 50);
        return selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getMerchantId, merchantId)
                .orderByDesc(MerchantWithdrawRequest::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long sumAmountByMerchantSince(String merchantId, Instant since) {
        List<MerchantWithdrawRequest> rows = selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getMerchantId, merchantId)
                .ge(MerchantWithdrawRequest::getCreatedAt, since)
                .in(MerchantWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (MerchantWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }
}
