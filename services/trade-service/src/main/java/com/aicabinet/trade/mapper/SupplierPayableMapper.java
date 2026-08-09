package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SupplierPayable;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SupplierPayableMapper extends BaseTradeMapper<SupplierPayable> {

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
