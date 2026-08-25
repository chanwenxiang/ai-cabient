package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SupplierPayment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SupplierPaymentMapper extends BaseTradeMapper<SupplierPayment> {

    default List<SupplierPayment> findByPayableIdOrderByCreatedAtAsc(Long payableId) {
        return selectList(Wrappers.<SupplierPayment>lambdaQuery()
                .eq(SupplierPayment::getPayableId, payableId)
                .orderByAsc(SupplierPayment::getCreatedAt)
                .orderByAsc(SupplierPayment::getPaymentId));
    }

    default Optional<SupplierPayment> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<SupplierPayment>lambdaQuery()
                .eq(SupplierPayment::getIdempotencyKey, idempotencyKey.trim())));
    }
}
