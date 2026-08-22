package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SupplierPayable;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SupplierPayableMapper extends BaseTradeMapper<SupplierPayable> {

    SupplierPayable _findByIdForUpdateRaw(@Param("payableId") Long payableId);

    SupplierPayable _findByPurchaseOrderIdForUpdateRaw(@Param("purchaseOrderId") Long purchaseOrderId);

    default Optional<SupplierPayable> findByIdForUpdate(Long payableId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(payableId));
    }

    default Optional<SupplierPayable> findByPurchaseOrderIdForUpdate(Long purchaseOrderId) {
        return Optional.ofNullable(_findByPurchaseOrderIdForUpdateRaw(purchaseOrderId));
    }

    default Optional<SupplierPayable> findByPurchaseOrderId(Long purchaseOrderId) {
        return Optional.ofNullable(selectOne(Wrappers.<SupplierPayable>lambdaQuery()
                .eq(SupplierPayable::getPurchaseOrderId, purchaseOrderId)));
    }

    default List<SupplierPayable> findAllByOrderByDueDateAsc() {
        return selectList(Wrappers.<SupplierPayable>lambdaQuery()
                .orderByAsc(SupplierPayable::getDueDate)
                .orderByDesc(SupplierPayable::getPayableId));
    }
}
