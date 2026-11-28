package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SupplierPayment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    @Insert("INSERT INTO supplier_payment "
            + "(supplier_id, payable_id, amount_cents, operator_id, notes, idempotency_key, created_at) "
            + "VALUES (#{p.supplierId}, #{p.payableId}, #{p.amountCents}, #{p.operatorId}, #{p.notes}, "
            + "#{p.idempotencyKey}, #{p.createdAt}) "
            + "ON CONFLICT (idempotency_key) "
            + "WHERE idempotency_key IS NOT NULL AND idempotency_key <> '' DO NOTHING")
    int insertIfAbsent(@Param("p") SupplierPayment payment);

    default Optional<SupplierPayment> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<SupplierPayment>lambdaQuery()
                .eq(SupplierPayment::getIdempotencyKey, idempotencyKey.trim())));
    }
}
