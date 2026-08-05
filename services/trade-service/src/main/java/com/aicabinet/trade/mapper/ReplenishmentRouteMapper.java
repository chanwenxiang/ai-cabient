package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReplenishmentRouteMapper extends BaseTradeMapper<ReplenishmentRoute> {

    /** 列表默认按路线 ID 正序，与运营台 ID 列升序约定一致。 */
    default List<ReplenishmentRoute> findAllByOrderByRouteIdAsc() {
    return selectList(Wrappers.<ReplenishmentRoute>lambdaQuery().orderByAsc(ReplenishmentRoute::getRouteId));
    }

}
