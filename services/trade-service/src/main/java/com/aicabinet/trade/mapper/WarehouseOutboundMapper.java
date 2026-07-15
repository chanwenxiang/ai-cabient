package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseOutboundMapper extends BaseTradeMapper<WarehouseOutbound> {

    default List<WarehouseOutbound> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<WarehouseOutbound>lambdaQuery().orderByDesc(WarehouseOutbound::getCreatedAt));
    }

    default Optional<WarehouseOutbound> findByRouteId(Long routeId) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseOutbound>lambdaQuery().eq(WarehouseOutbound::getRouteId, routeId)));
    }

}
