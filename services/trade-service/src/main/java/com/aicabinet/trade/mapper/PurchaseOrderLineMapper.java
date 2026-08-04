package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseOrderLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PurchaseOrderLineMapper extends BaseTradeMapper<PurchaseOrderLine> {

    default List<PurchaseOrderLine> findByPurchaseOrderIdOrderByLineIdAsc(Long purchaseOrderId) {
    return selectList(Wrappers.<PurchaseOrderLine>lambdaQuery().eq(PurchaseOrderLine::getPurchaseOrderId, purchaseOrderId).orderByAsc(PurchaseOrderLine::getLineId));
    }

}
