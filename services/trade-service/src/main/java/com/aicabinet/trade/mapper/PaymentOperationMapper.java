package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PaymentOperation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface PaymentOperationMapper extends BaseTradeMapper<PaymentOperation> {

    default Optional<PaymentOperation> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(selectOne(Wrappers.<PaymentOperation>lambdaQuery().eq(PaymentOperation::getIdempotencyKey, idempotencyKey)));
    }

    default Page<PaymentOperation> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PaymentOperation>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<PaymentOperation>lambdaQuery().eq(PaymentOperation::getUserId, userId).orderByDesc(PaymentOperation::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countRefundsSince(Long userId, java.time.Instant since) {
        Long n = selectCount(Wrappers.<PaymentOperation>lambdaQuery()
                .eq(PaymentOperation::getUserId, userId)
                .eq(PaymentOperation::getOperationType, "REFUND")
                .ge(PaymentOperation::getCreatedAt, since));
        return n == null ? 0L : n;
    }

}
