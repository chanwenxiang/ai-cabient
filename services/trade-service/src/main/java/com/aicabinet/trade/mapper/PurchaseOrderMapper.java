package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PurchaseOrderMapper extends BaseTradeMapper<PurchaseOrder> {

    PurchaseOrder _findByIdForUpdateRaw(@Param("purchaseOrderId") Long purchaseOrderId);

    default Optional<PurchaseOrder> findByIdForUpdate(Long purchaseOrderId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(purchaseOrderId));
    }

    default List<PurchaseOrder> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt));
    }

}
