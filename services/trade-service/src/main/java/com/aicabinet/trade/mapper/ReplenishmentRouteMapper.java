package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReplenishmentRouteMapper extends BaseTradeMapper<ReplenishmentRoute> {

    default List<ReplenishmentRoute> findAllByOrderByPlannedDateDesc() {
    return selectList(Wrappers.<ReplenishmentRoute>lambdaQuery().orderByDesc(ReplenishmentRoute::getPlannedDate));
    }

}
