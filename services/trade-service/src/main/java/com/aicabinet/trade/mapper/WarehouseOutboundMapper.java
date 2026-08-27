package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WarehouseOutboundMapper extends BaseTradeMapper<WarehouseOutbound> {

    WarehouseOutbound findByIdForUpdateRaw(@Param("outboundId") Long outboundId);

    default Optional<WarehouseOutbound> findByIdForUpdate(Long outboundId) {
        return Optional.ofNullable(findByIdForUpdateRaw(outboundId));
    }

    default List<WarehouseOutbound> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<WarehouseOutbound>lambdaQuery().orderByDesc(WarehouseOutbound::getCreatedAt));
    }

    default Optional<WarehouseOutbound> findByRouteId(Long routeId) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseOutbound>lambdaQuery().eq(WarehouseOutbound::getRouteId, routeId)));
    }

    /** page 为 0-based。 */
    default Page<WarehouseOutbound> searchPage(String keyword, String warehouseId, int page, int size) {
        var query = Wrappers.<WarehouseOutbound>lambdaQuery().orderByDesc(WarehouseOutbound::getCreatedAt);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(WarehouseOutbound::getWarehouseId, warehouseId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> {
                w.like(WarehouseOutbound::getNotes, kw)
                        .or().like(WarehouseOutbound::getStatus, kw);
                try {
                    long id = Long.parseLong(kw);
                    w.or().eq(WarehouseOutbound::getOutboundId, id);
                } catch (NumberFormatException ignored) {
                    // not numeric id
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
