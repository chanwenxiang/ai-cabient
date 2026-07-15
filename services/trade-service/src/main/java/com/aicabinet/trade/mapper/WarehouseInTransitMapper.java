package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseInTransit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseInTransitMapper extends BaseTradeMapper<WarehouseInTransit> {

    default List<WarehouseInTransit> findByDeviceIdAndStatus(String deviceId, String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status));
    }

    default List<WarehouseInTransit> findByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getOutboundId, outboundId).eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status));
    }

    default List<WarehouseInTransit> findByStatusOrderByCreatedAtAsc(String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getStatus, status).orderByAsc(WarehouseInTransit::getCreatedAt));
    }

    default boolean existsByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status) {
    return selectCount(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getOutboundId, outboundId).eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status)) > 0;
    }

}
