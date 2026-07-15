package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReplenishmentTaskMapper extends BaseTradeMapper<ReplenishmentTask> {

    default List<ReplenishmentTask> findByRouteId(Long routeId) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getRouteId, routeId));
    }

    default List<ReplenishmentTask> findByOutboundId(Long outboundId) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getOutboundId, outboundId));
    }

    default List<ReplenishmentTask> findByDeviceIdAndStatusIn(String deviceId, List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getDeviceId, deviceId).in(ReplenishmentTask::getStatus, statuses));
    }

    default List<ReplenishmentTask> findByAssigneeUserIdAndStatus(Long assigneeUserId, String status) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getAssigneeUserId, assigneeUserId).eq(ReplenishmentTask::getStatus, status));
    }

    default List<ReplenishmentTask> findByAssigneeUserIdAndStatusIn(Long assigneeUserId, List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getAssigneeUserId, assigneeUserId).in(ReplenishmentTask::getStatus, statuses));
    }

    default List<ReplenishmentTask> findByStatusIn(List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses));
    }

    default long countByStatusIn(List<String> statuses) {
    Long c = selectCount(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses));
    return c == null ? 0 : c;
    }

    default List<ReplenishmentTask> findTop10ByStatusInOrderByCreatedAtAsc(List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses).orderByAsc(ReplenishmentTask::getCreatedAt).last("LIMIT 10"));
    }

    Instant _findLastCompletedAtByDeviceId(@Param("deviceId") String deviceId);

    default Optional<Instant> findLastCompletedAtByDeviceId(String deviceId) {
        return Optional.ofNullable(_findLastCompletedAtByDeviceId(deviceId));
    }

}
