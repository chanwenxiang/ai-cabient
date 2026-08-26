package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseMovement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseMovementMapper extends BaseTradeMapper<WarehouseMovement> {

    default List<WarehouseMovement> findTop100ByWarehouseIdOrderByCreatedAtDesc(String warehouseId) {
    return selectList(Wrappers.<WarehouseMovement>lambdaQuery().eq(WarehouseMovement::getWarehouseId, warehouseId).orderByDesc(WarehouseMovement::getCreatedAt).last("LIMIT 100"));
    }

    /** page 为 0-based。 */
    default Page<WarehouseMovement> searchPage(String warehouseId, String keyword, int page, int size) {
        var query = Wrappers.<WarehouseMovement>lambdaQuery().orderByDesc(WarehouseMovement::getCreatedAt);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(WarehouseMovement::getWarehouseId, warehouseId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(WarehouseMovement::getSkuId, kw)
                    .or().like(WarehouseMovement::getRefId, kw)
                    .or().like(WarehouseMovement::getMovementType, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
