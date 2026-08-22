package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseStocktake;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WarehouseStocktakeMapper extends BaseTradeMapper<WarehouseStocktake> {

    WarehouseStocktake _findByIdForUpdateRaw(@Param("stocktakeId") Long stocktakeId);

    default Optional<WarehouseStocktake> findByIdForUpdate(Long stocktakeId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(stocktakeId));
    }

    default List<WarehouseStocktake> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.<WarehouseStocktake>lambdaQuery()
                .orderByDesc(WarehouseStocktake::getCreatedAt)
                .orderByDesc(WarehouseStocktake::getStocktakeId));
    }
}
