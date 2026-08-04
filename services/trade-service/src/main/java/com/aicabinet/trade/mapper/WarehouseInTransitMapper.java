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

    default List<WarehouseInTransit> findByStatusAndCreatedAtBefore(String status, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff)
                .orderByAsc(WarehouseInTransit::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByStatusAndCreatedAtBefore(String status, java.time.Instant cutoff) {
        Long c = selectCount(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff));
        return c == null ? 0 : c;
    }

    default long countByStatusAndCreatedAtBeforeAndDeviceIdIn(
            String status, java.time.Instant cutoff, java.util.Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff)
                .in(WarehouseInTransit::getDeviceId, deviceIds));
        return c == null ? 0 : c;
    }

    default boolean existsByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status) {
    return selectCount(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getOutboundId, outboundId).eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status)) > 0;
    }

}
