package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PurchaseOrderMapper extends BaseTradeMapper<PurchaseOrder> {

    default List<PurchaseOrder> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt));
    }

}
