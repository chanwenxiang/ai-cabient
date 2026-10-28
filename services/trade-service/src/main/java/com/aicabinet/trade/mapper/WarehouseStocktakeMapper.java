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

    /**
     * 当日前缀（如 `PD20261028-`）下已有的最大单号；无则 null。
     * 固定三位流水 ⇒ 字符串降序与数值降序一致，可直接 orderByDesc。
     */
    default String findMaxNoByPrefix(String prefix) {
        WarehouseStocktake row = selectOne(Wrappers.<WarehouseStocktake>lambdaQuery()
                .select(WarehouseStocktake::getStocktakeNo)
                .likeRight(WarehouseStocktake::getStocktakeNo, prefix)
                .orderByDesc(WarehouseStocktake::getStocktakeNo)
                .last("LIMIT 1"));
        return row == null ? null : row.getStocktakeNo();
    }

    /** 单号是否已被占用（唯一索引兜底之前先自查，避免脏号导致整单 500）。 */
    default boolean existsByNo(String stocktakeNo) {
        Long n = selectCount(Wrappers.<WarehouseStocktake>lambdaQuery()
                .eq(WarehouseStocktake::getStocktakeNo, stocktakeNo));
        return n != null && n > 0;
    }
}
