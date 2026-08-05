package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReplenishmentTaskMapper extends BaseTradeMapper<ReplenishmentTask> {

    default List<ReplenishmentTask> findByRouteId(Long routeId) {
    return selectList(
        Wrappers.<ReplenishmentTask>lambdaQuery()
            .eq(ReplenishmentTask::getRouteId, routeId)
            .orderByAsc(ReplenishmentTask::getTaskId));
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

    default List<ReplenishmentTask> findByStatusInOrderByCreatedAtAsc(List<String> statuses, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .in(ReplenishmentTask::getStatus, statuses)
                .orderByAsc(ReplenishmentTask::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByStatusInAndDeviceIdIn(List<String> statuses, Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<ReplenishmentTask>lambdaQuery()
                .in(ReplenishmentTask::getStatus, statuses)
                .in(ReplenishmentTask::getDeviceId, deviceIds));
        return c == null ? 0 : c;
    }

    /** 已签到且仍进行中、签到时间早于截止：超时收口防占柜。 */
    default List<ReplenishmentTask> findByStatusAndCheckInAtBefore(String status, Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .eq(ReplenishmentTask::getStatus, status)
                .isNotNull(ReplenishmentTask::getCheckInAt)
                .lt(ReplenishmentTask::getCheckInAt, cutoff)
                .orderByAsc(ReplenishmentTask::getCheckInAt)
                .last("LIMIT " + lim));
    }

    Instant _findLastCompletedAtByDeviceId(@Param("deviceId") String deviceId);

    default Optional<Instant> findLastCompletedAtByDeviceId(String deviceId) {
        return Optional.ofNullable(_findLastCompletedAtByDeviceId(deviceId));
    }

}
