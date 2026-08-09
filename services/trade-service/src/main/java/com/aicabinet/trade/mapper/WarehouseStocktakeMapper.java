package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseStocktake;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WarehouseStocktakeMapper extends BaseTradeMapper<WarehouseStocktake> {

    default List<WarehouseStocktake> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.<WarehouseStocktake>lambdaQuery()
                .orderByDesc(WarehouseStocktake::getCreatedAt)
                .orderByDesc(WarehouseStocktake::getStocktakeId));
    }
}
