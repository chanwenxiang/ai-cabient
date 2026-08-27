package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceOpsEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Mapper
public interface DeviceOpsEventMapper extends BaseTradeMapper<DeviceOpsEvent> {

    record DeviceOpsEventSearchCriteria(
            Collection<String> deviceIds,
            String eventType,
            String severity,
            String deviceId,
            Instant from,
            Instant to,
            int page,
            int size,
            boolean eventIdAsc) {}

    default Page<DeviceOpsEvent> search(DeviceOpsEventSearchCriteria criteria) {
        var q = Wrappers.<DeviceOpsEvent>lambdaQuery();
        if (criteria.eventIdAsc()) {
            q.orderByAsc(DeviceOpsEvent::getEventId);
        } else {
            q.orderByDesc(DeviceOpsEvent::getEventId);
        }
        if (criteria.deviceId() != null && !criteria.deviceId().isBlank()) {
            q.eq(DeviceOpsEvent::getDeviceId, criteria.deviceId().trim());
        } else if (criteria.deviceIds() != null && !criteria.deviceIds().isEmpty()) {
            q.in(DeviceOpsEvent::getDeviceId, criteria.deviceIds());
        }
        if (criteria.eventType() != null && !criteria.eventType().isBlank()) {
            q.eq(DeviceOpsEvent::getEventType, criteria.eventType().trim());
        }
        if (criteria.severity() != null && !criteria.severity().isBlank()) {
            q.eq(DeviceOpsEvent::getSeverity, criteria.severity().trim());
        }
        if (criteria.from() != null) {
            q.ge(DeviceOpsEvent::getCreatedAt, criteria.from());
        }
        if (criteria.to() != null) {
            q.le(DeviceOpsEvent::getCreatedAt, criteria.to());
        }
        return selectPage(new Page<>(criteria.page() + 1L, criteria.size()), q);
    }

    default List<DeviceOpsEvent> findRecent(int limit) {
        return selectList(Wrappers.<DeviceOpsEvent>lambdaQuery()
                .orderByDesc(DeviceOpsEvent::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }
}
