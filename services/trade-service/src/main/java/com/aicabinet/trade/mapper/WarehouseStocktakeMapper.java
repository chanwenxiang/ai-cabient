package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseStocktake;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WarehouseStocktakeMapper extends BaseTradeMapper<WarehouseStocktake> {

    WarehouseStocktake findByIdForUpdateRaw(@Param("stocktakeId") Long stocktakeId);

    default Optional<WarehouseStocktake> findByIdForUpdate(Long stocktakeId) {
        return Optional.ofNullable(findByIdForUpdateRaw(stocktakeId));
    }

    default List<WarehouseStocktake> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.<WarehouseStocktake>lambdaQuery()
                .orderByDesc(WarehouseStocktake::getCreatedAt)
                .orderByDesc(WarehouseStocktake::getStocktakeId));
    }

    /** page 为 0-based。 */
    default Page<WarehouseStocktake> searchPage(String status, String warehouseId, int page, int size) {
        var query = Wrappers.<WarehouseStocktake>lambdaQuery()
                .orderByDesc(WarehouseStocktake::getCreatedAt)
                .orderByDesc(WarehouseStocktake::getStocktakeId);
        if (status != null && !status.isBlank()) {
            query.eq(WarehouseStocktake::getStatus, status.trim().toUpperCase());
        }
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(WarehouseStocktake::getWarehouseId, warehouseId.trim());
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }
}
