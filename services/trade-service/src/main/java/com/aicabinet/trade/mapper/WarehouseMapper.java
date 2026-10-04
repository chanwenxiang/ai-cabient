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

    /** 负责人名下第一家启用仓（分仓日常采购默认入库）。 */
    default java.util.Optional<Warehouse> findFirstActiveByManagerUserId(Long managerUserId) {
        if (managerUserId == null) {
            return java.util.Optional.empty();
        }
        Warehouse row = selectOne(Wrappers.<Warehouse>lambdaQuery()
                .eq(Warehouse::getManagerUserId, managerUserId)
                .eq(Warehouse::getStatus, "ACTIVE")
                .orderByAsc(Warehouse::getWarehouseId)
                .last("LIMIT 1"));
        return java.util.Optional.ofNullable(row);
    }
}
