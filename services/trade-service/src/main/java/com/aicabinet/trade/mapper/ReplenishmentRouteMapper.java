package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReplenishmentRouteMapper extends BaseTradeMapper<ReplenishmentRoute> {

    ReplenishmentRoute findByIdForUpdateRaw(@Param("routeId") Long routeId);

    default Optional<ReplenishmentRoute> findByIdForUpdate(Long routeId) {
        return Optional.ofNullable(findByIdForUpdateRaw(routeId));
    }

    /** 列表默认按路线 ID 正序，与运营台 ID 列升序约定一致。 */
    default List<ReplenishmentRoute> findAllByOrderByRouteIdAsc() {
    return selectList(Wrappers.<ReplenishmentRoute>lambdaQuery().orderByAsc(ReplenishmentRoute::getRouteId));
    }

    /** page 为 0-based；routeIds 为空时不加 IN 条件。 */
    default Page<ReplenishmentRoute> searchPage(java.util.Collection<Long> routeIds, int page, int size) {
        var query = Wrappers.<ReplenishmentRoute>lambdaQuery().orderByAsc(ReplenishmentRoute::getRouteId);
        if (routeIds != null && !routeIds.isEmpty()) {
            query.in(ReplenishmentRoute::getRouteId, routeIds);
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
