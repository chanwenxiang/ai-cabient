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
    default Page<DeviceOpsEvent> search(Collection<String> deviceIds, String eventType, String severity,
                                        String deviceId, Instant from, Instant to,
                                        int page, int size, boolean eventIdAsc) {
        var q = Wrappers.<DeviceOpsEvent>lambdaQuery();
        if (eventIdAsc) {
            q.orderByAsc(DeviceOpsEvent::getEventId);
        } else {
            q.orderByDesc(DeviceOpsEvent::getEventId);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(DeviceOpsEvent::getDeviceId, deviceId.trim());
        } else if (deviceIds != null && !deviceIds.isEmpty()) {
            q.in(DeviceOpsEvent::getDeviceId, deviceIds);
        }
        if (eventType != null && !eventType.isBlank()) {
            q.eq(DeviceOpsEvent::getEventType, eventType.trim());
        }
        if (severity != null && !severity.isBlank()) {
            q.eq(DeviceOpsEvent::getSeverity, severity.trim());
        }
        if (from != null) {
            q.ge(DeviceOpsEvent::getCreatedAt, from);
        }
        if (to != null) {
            q.le(DeviceOpsEvent::getCreatedAt, to);
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }

    /** @deprecated use overload with severity/deviceId */
    default Page<DeviceOpsEvent> search(Collection<String> deviceIds, String eventType, Instant from, Instant to,
                                        int page, int size, boolean eventIdAsc) {
        return search(deviceIds, eventType, null, null, from, to, page, size, eventIdAsc);
    }

    default List<DeviceOpsEvent> findRecent(int limit) {
        return selectList(Wrappers.<DeviceOpsEvent>lambdaQuery()
                .orderByDesc(DeviceOpsEvent::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }
}
