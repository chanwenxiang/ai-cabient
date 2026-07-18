package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseReturnLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PurchaseReturnLineMapper extends BaseTradeMapper<PurchaseReturnLine> {

    default List<PurchaseReturnLine> findByReturnIdOrderByLineIdAsc(Long returnId) {
        return selectList(Wrappers.<PurchaseReturnLine>lambdaQuery()
                .eq(PurchaseReturnLine::getReturnId, returnId)
                .orderByAsc(PurchaseReturnLine::getLineId));
    }
}
