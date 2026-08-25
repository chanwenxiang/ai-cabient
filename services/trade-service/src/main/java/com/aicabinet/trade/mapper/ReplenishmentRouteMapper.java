package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReplenishmentRouteMapper extends BaseTradeMapper<ReplenishmentRoute> {

    ReplenishmentRoute _findByIdForUpdateRaw(@Param("routeId") Long routeId);

    default Optional<ReplenishmentRoute> findByIdForUpdate(Long routeId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(routeId));
    }

    /** 列表默认按路线 ID 正序，与运营台 ID 列升序约定一致。 */
    default List<ReplenishmentRoute> findAllByOrderByRouteIdAsc() {
    return selectList(Wrappers.<ReplenishmentRoute>lambdaQuery().orderByAsc(ReplenishmentRoute::getRouteId));
    }

}
