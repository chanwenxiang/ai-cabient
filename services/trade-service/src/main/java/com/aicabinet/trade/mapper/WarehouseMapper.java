package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Warehouse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseMapper extends BaseTradeMapper<Warehouse> {

    /** page 为 0-based。 */
    default Page<Warehouse> searchPage(String keyword, int page, int size) {
        var query = Wrappers.<Warehouse>lambdaQuery().orderByAsc(Warehouse::getWarehouseId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(Warehouse::getWarehouseId, kw)
                    .or().like(Warehouse::getWarehouseName, kw)
                    .or().like(Warehouse::getAddress, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }
}
