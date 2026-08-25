package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WarehouseStocktakeLineMapper extends BaseTradeMapper<WarehouseStocktakeLine> {

    default List<WarehouseStocktakeLine> findByStocktakeIdOrderByLineIdAsc(Long stocktakeId) {
        return selectList(Wrappers.<WarehouseStocktakeLine>lambdaQuery()
                .eq(WarehouseStocktakeLine::getStocktakeId, stocktakeId)
                .orderByAsc(WarehouseStocktakeLine::getLineId));
    }
}
