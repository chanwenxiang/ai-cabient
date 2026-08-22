package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WarehouseOutboundMapper extends BaseTradeMapper<WarehouseOutbound> {

    WarehouseOutbound _findByIdForUpdateRaw(@Param("outboundId") Long outboundId);

    default Optional<WarehouseOutbound> findByIdForUpdate(Long outboundId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(outboundId));
    }

    default List<WarehouseOutbound> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<WarehouseOutbound>lambdaQuery().orderByDesc(WarehouseOutbound::getCreatedAt));
    }

    default Optional<WarehouseOutbound> findByRouteId(Long routeId) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseOutbound>lambdaQuery().eq(WarehouseOutbound::getRouteId, routeId)));
    }

}
